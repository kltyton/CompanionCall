package com.kltyton.companioncall.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kltyton.companioncall.storage.pet.PetRecord;
import com.kltyton.companioncall.storage.transfer.TransferRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Single-world ordered metadata persistence; stores no reconstructable entity snapshots. */
public final class RecallStore implements AutoCloseable {
    public record Snapshot(int schema, long nextOrder, List<PetRecord> pets, Map<UUID, UUID> selections,
                           List<TransferRecord> transfers, Map<UUID, Long> protections,
                           Map<UUID, Set<UUID>> blacklists, Map<UUID, UUID> bindings) {
        public Snapshot {
            if (schema != 1 && schema != 2) throw new IllegalArgumentException("Unsupported recall store schema: " + schema);
            pets = List.copyOf(pets);
            selections = Map.copyOf(selections);
            transfers = List.copyOf(transfers);
            protections = Map.copyOf(protections);
            blacklists = schema == 1 && blacklists == null ? Map.of() : blacklists.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
            bindings = schema == 1 && bindings == null ? Map.of() : Map.copyOf(bindings);
        }
        public static Snapshot empty() {
            return new Snapshot(2, 0, List.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private static final class WriteBatch {
        Snapshot snapshot;
        final CompletableFuture<Void> completion = new CompletableFuture<>();

        WriteBatch(Snapshot snapshot) { this.snapshot = snapshot; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Object writeLock = new Object();
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
        var thread = new Thread(runnable, "CompanionCall persistence");
        thread.setDaemon(true);
        return thread;
    });
    private WriteBatch pending;
    private boolean draining;
    private boolean closed;
    private volatile IOException failure;

    public RecallStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("recall.json");
    }

    public Snapshot read() throws IOException {
        if (!Files.exists(file)) return Snapshot.empty();
        var snapshot = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
        if (snapshot == null) throw new IOException("Empty recall store: " + file);
        return snapshot;
    }

    /** Completes after this full state, or a newer full state, has been durably published. */
    public CompletableFuture<Void> write(Snapshot snapshot) {
        synchronized (writeLock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Recall store is closed"));
            if (failure != null) return CompletableFuture.failedFuture(failure);
            if (pending == null) pending = new WriteBatch(snapshot);
            else pending.snapshot = snapshot;
            var completion = pending.completion;
            if (!draining) {
                draining = true;
                writer.execute(this::drain);
            }
            return completion;
        }
    }

    private void drain() {
        while (true) {
            WriteBatch batch;
            synchronized (writeLock) {
                batch = pending;
                pending = null;
                if (batch == null) {
                    draining = false;
                    return;
                }
            }
            // A new submit gets a new future once this snapshot/future pair has been removed.
            try {
                publish(batch.snapshot);
            } catch (IOException | RuntimeException error) {
                IOException problem = error instanceof IOException io ? io : new IOException("Could not encode recall metadata", error);
                WriteBatch waiting;
                synchronized (writeLock) {
                    failure = problem;
                    waiting = pending;
                    pending = null;
                    draining = false;
                }
                batch.completion.completeExceptionally(problem);
                if (waiting != null) waiting.completion.completeExceptionally(problem);
                return;
            }
            batch.completion.complete(null);
        }
    }

    private void publish(Snapshot snapshot) throws IOException {
        byte[] bytes = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        Path staging = file.resolveSibling("recall.pending.json");
        try (var channel = FileChannel.open(staging, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public IOException failure() { return failure; }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            closed = true;
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IOException("Recall metadata writer did not finish before server shutdown");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving recall metadata", error);
        }
        if (failure != null) throw failure;
    }
}
