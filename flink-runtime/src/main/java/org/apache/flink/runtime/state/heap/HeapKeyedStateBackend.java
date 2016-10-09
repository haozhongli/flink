/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.state.heap;

import org.apache.commons.io.IOUtils;
import org.apache.flink.api.common.state.FoldingState;
import org.apache.flink.api.common.state.FoldingStateDescriptor;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ReducingState;
import org.apache.flink.api.common.state.ReducingStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.fs.FSDataInputStream;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.runtime.query.TaskKvStateRegistry;
import org.apache.flink.runtime.state.AbstractKeyedStateBackend;
import org.apache.flink.runtime.state.ArrayListSerializer;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.DoneFuture;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupRangeOffsets;
import org.apache.flink.runtime.state.KeyGroupsStateHandle;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RunnableFuture;

/**
 * A {@link AbstractKeyedStateBackend} that keeps state on the Java Heap and will serialize state to
 * streams provided by a {@link org.apache.flink.runtime.state.CheckpointStreamFactory} upon
 * checkpointing.
 *
 * @param <K> The key by which state is keyed.
 */
public class HeapKeyedStateBackend<K> extends AbstractKeyedStateBackend<K> {

	private static final Logger LOG = LoggerFactory.getLogger(HeapKeyedStateBackend.class);

	/**
	 * Map of state tables that stores all state of key/value states. We store it centrally so
	 * that we can easily checkpoint/restore it.
	 *
	 * <p>The actual parameters of StateTable are {@code StateTable<NamespaceT, Map<KeyT, StateT>>}
	 * but we can't put them here because different key/value states with different types and
	 * namespace types share this central list of tables.
	 */
	private final Map<String, StateTable<K, ?, ?>> stateTables = new HashMap<>();

	public HeapKeyedStateBackend(
			TaskKvStateRegistry kvStateRegistry,
			TypeSerializer<K> keySerializer,
			ClassLoader userCodeClassLoader,
			int numberOfKeyGroups,
			KeyGroupRange keyGroupRange) {

		super(kvStateRegistry, keySerializer, userCodeClassLoader, numberOfKeyGroups, keyGroupRange);

		LOG.info("Initializing heap keyed state backend with stream factory.");
	}

	public HeapKeyedStateBackend(
			TaskKvStateRegistry kvStateRegistry,
			TypeSerializer<K> keySerializer,
			ClassLoader userCodeClassLoader,
			int numberOfKeyGroups,
			KeyGroupRange keyGroupRange,
			List<KeyGroupsStateHandle> restoredState) throws Exception {
		super(kvStateRegistry, keySerializer, userCodeClassLoader, numberOfKeyGroups, keyGroupRange);

		LOG.info("Initializing heap keyed state backend from snapshot.");

		if (LOG.isDebugEnabled()) {
			LOG.debug("Restoring snapshot from state handles: {}.", restoredState);
		}

		restorePartitionedState(restoredState);
	}

	// ------------------------------------------------------------------------
	//  state backend operations
	// ------------------------------------------------------------------------
	@SuppressWarnings("unchecked")
	@Override
	public <N, V> ValueState<V> createValueState(TypeSerializer<N> namespaceSerializer, ValueStateDescriptor<V> stateDesc) throws Exception {
		StateTable<K, N, V> stateTable = (StateTable<K, N, V>) stateTables.get(stateDesc.getName());

		if (stateTable == null) {
			stateTable = new StateTable<>(stateDesc.getSerializer(), namespaceSerializer, keyGroupRange);
			stateTables.put(stateDesc.getName(), stateTable);
		}

		return new HeapValueState<>(this, stateDesc, stateTable, keySerializer, namespaceSerializer);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <N, T> ListState<T> createListState(TypeSerializer<N> namespaceSerializer, ListStateDescriptor<T> stateDesc) throws Exception {
		StateTable<K, N, ArrayList<T>> stateTable = (StateTable<K, N, ArrayList<T>>) stateTables.get(stateDesc.getName());

		if (stateTable == null) {
			stateTable = new StateTable<>(new ArrayListSerializer<>(stateDesc.getSerializer()), namespaceSerializer, keyGroupRange);
			stateTables.put(stateDesc.getName(), stateTable);
		}

		return new HeapListState<>(this, stateDesc, stateTable, keySerializer, namespaceSerializer);
	}
	@SuppressWarnings("unchecked")
	@Override
	public <N, T> ReducingState<T> createReducingState(TypeSerializer<N> namespaceSerializer, ReducingStateDescriptor<T> stateDesc) throws Exception {
		StateTable<K, N, T> stateTable = (StateTable<K, N, T>) stateTables.get(stateDesc.getName());

		if (stateTable == null) {
			stateTable = new StateTable<>(stateDesc.getSerializer(), namespaceSerializer, keyGroupRange);
			stateTables.put(stateDesc.getName(), stateTable);
		}

		return new HeapReducingState<>(this, stateDesc, stateTable, keySerializer, namespaceSerializer);
	}
	@SuppressWarnings("unchecked")
	@Override
	protected <N, T, ACC> FoldingState<T, ACC> createFoldingState(TypeSerializer<N> namespaceSerializer, FoldingStateDescriptor<T, ACC> stateDesc) throws Exception {
		StateTable<K, N, ACC> stateTable = (StateTable<K, N, ACC>) stateTables.get(stateDesc.getName());

		if (stateTable == null) {
			stateTable = new StateTable<>(stateDesc.getSerializer(), namespaceSerializer, keyGroupRange);
			stateTables.put(stateDesc.getName(), stateTable);
		}

		return new HeapFoldingState<>(this, stateDesc, stateTable, keySerializer, namespaceSerializer);
	}

	@Override
	@SuppressWarnings("unchecked")
	public RunnableFuture<KeyGroupsStateHandle> snapshot(
			long checkpointId,
			long timestamp,
			CheckpointStreamFactory streamFactory) throws Exception {

		if (stateTables.isEmpty()) {
			return new DoneFuture<>(null);
		}

		try (CheckpointStreamFactory.CheckpointStateOutputStream stream = streamFactory.
				createCheckpointStateOutputStream(checkpointId, timestamp)) {

			DataOutputViewStreamWrapper outView = new DataOutputViewStreamWrapper(stream);

			Preconditions.checkState(stateTables.size() <= Short.MAX_VALUE,
					"Too many KV-States: " + stateTables.size() +
							". Currently at most " + Short.MAX_VALUE + " states are supported");

			outView.writeShort(stateTables.size());

			Map<String, Integer> kVStateToId = new HashMap<>(stateTables.size());

			for (Map.Entry<String, StateTable<K, ?, ?>> kvState : stateTables.entrySet()) {

				outView.writeUTF(kvState.getKey());

				TypeSerializer<?> namespaceSerializer = kvState.getValue().getNamespaceSerializer();
				TypeSerializer<?> stateSerializer = kvState.getValue().getStateSerializer();

				InstantiationUtil.serializeObject(stream, namespaceSerializer);
				InstantiationUtil.serializeObject(stream, stateSerializer);

				kVStateToId.put(kvState.getKey(), kVStateToId.size());
			}

			int offsetCounter = 0;
			long[] keyGroupRangeOffsets = new long[keyGroupRange.getNumberOfKeyGroups()];

			for (int keyGroupIndex = keyGroupRange.getStartKeyGroup(); keyGroupIndex <= keyGroupRange.getEndKeyGroup(); keyGroupIndex++) {
				keyGroupRangeOffsets[offsetCounter++] = stream.getPos();
				outView.writeInt(keyGroupIndex);
				for (Map.Entry<String, StateTable<K, ?, ?>> kvState : stateTables.entrySet()) {
					outView.writeShort(kVStateToId.get(kvState.getKey()));
					writeStateTableForKeyGroup(outView, kvState.getValue(), keyGroupIndex);
				}
			}

			StreamStateHandle streamStateHandle = stream.closeAndGetHandle();

			KeyGroupRangeOffsets offsets = new KeyGroupRangeOffsets(keyGroupRange, keyGroupRangeOffsets);
			final KeyGroupsStateHandle keyGroupsStateHandle = new KeyGroupsStateHandle(offsets, streamStateHandle);
			return new DoneFuture<>(keyGroupsStateHandle);
		}
	}

	private <N, S> void writeStateTableForKeyGroup(
			DataOutputView outView,
			StateTable<K, N, S> stateTable,
			int keyGroupIndex) throws IOException {

		TypeSerializer<N> namespaceSerializer = stateTable.getNamespaceSerializer();
		TypeSerializer<S> stateSerializer = stateTable.getStateSerializer();

		Map<N, Map<K, S>> namespaceMap = stateTable.get(keyGroupIndex);
		if (namespaceMap == null) {
			outView.writeByte(0);
		} else {
			outView.writeByte(1);

			// number of namespaces
			outView.writeInt(namespaceMap.size());
			for (Map.Entry<N, Map<K, S>> namespace : namespaceMap.entrySet()) {
				namespaceSerializer.serialize(namespace.getKey(), outView);

				Map<K, S> entryMap = namespace.getValue();

				// number of entries
				outView.writeInt(entryMap.size());
				for (Map.Entry<K, S> entry : entryMap.entrySet()) {
					keySerializer.serialize(entry.getKey(), outView);
					stateSerializer.serialize(entry.getValue(), outView);
				}
			}
		}
	}

	@SuppressWarnings({"unchecked"})
	private void restorePartitionedState(List<KeyGroupsStateHandle> state) throws Exception {

		int numRegisteredKvStates = 0;
		Map<Integer, String> kvStatesById = new HashMap<>();

		for (KeyGroupsStateHandle keyGroupsHandle : state) {

			if (keyGroupsHandle == null) {
				continue;
			}

			FSDataInputStream fsDataInputStream = null;

			try {

				fsDataInputStream = keyGroupsHandle.openInputStream();
				cancelStreamRegistry.registerClosable(fsDataInputStream);

				DataInputViewStreamWrapper inView = new DataInputViewStreamWrapper(fsDataInputStream);

				int numKvStates = inView.readShort();

				for (int i = 0; i < numKvStates; ++i) {
					String stateName = inView.readUTF();

					TypeSerializer<?> namespaceSerializer =
							InstantiationUtil.deserializeObject(fsDataInputStream, userCodeClassLoader);
					TypeSerializer<?> stateSerializer =
							InstantiationUtil.deserializeObject(fsDataInputStream, userCodeClassLoader);

					StateTable<K, ?, ?> stateTable = stateTables.get(stateName);

					//important: only create a new table we did not already create it previously
					if (null == stateTable) {
						stateTable = new StateTable<>(stateSerializer, namespaceSerializer, keyGroupRange);
						stateTables.put(stateName, stateTable);
						kvStatesById.put(numRegisteredKvStates, stateName);
						++numRegisteredKvStates;
					}
				}

				for (Tuple2<Integer, Long> groupOffset : keyGroupsHandle.getGroupRangeOffsets()) {
					int keyGroupIndex = groupOffset.f0;
					long offset = groupOffset.f1;
					fsDataInputStream.seek(offset);

					int writtenKeyGroupIndex = inView.readInt();
					assert writtenKeyGroupIndex == keyGroupIndex;

					for (int i = 0; i < numKvStates; i++) {
						int kvStateId = inView.readShort();

						byte isPresent = inView.readByte();
						if (isPresent == 0) {
							continue;
						}

						StateTable<K, ?, ?> stateTable = stateTables.get(kvStatesById.get(kvStateId));
						Preconditions.checkNotNull(stateTable);

						readStateTableForKeyGroup(inView, stateTable, keyGroupIndex);
					}
				}
			} finally {
				cancelStreamRegistry.unregisterClosable(fsDataInputStream);
				IOUtils.closeQuietly(fsDataInputStream);
			}
		}
	}

	private <N, S> void readStateTableForKeyGroup(
			DataInputView inView,
			StateTable<K, N, S> stateTable,
			int keyGroupIndex) throws IOException {

		TypeSerializer<N> namespaceSerializer = stateTable.getNamespaceSerializer();
		TypeSerializer<S> stateSerializer = stateTable.getStateSerializer();

		Map<N, Map<K, S>> namespaceMap = new HashMap<>();
		stateTable.set(keyGroupIndex, namespaceMap);

		int numNamespaces = inView.readInt();
		for (int k = 0; k < numNamespaces; k++) {
			N namespace = namespaceSerializer.deserialize(inView);
			Map<K, S> entryMap = new HashMap<>();
			namespaceMap.put(namespace, entryMap);

			int numEntries = inView.readInt();
			for (int l = 0; l < numEntries; l++) {
				K key = keySerializer.deserialize(inView);
				S state = stateSerializer.deserialize(inView);
				entryMap.put(key, state);
			}
		}
	}

	@Override
	public String toString() {
		return "HeapKeyedStateBackend";
	}

}
