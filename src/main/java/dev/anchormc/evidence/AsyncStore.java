package dev.anchormc.evidence;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 쓰기를 별도 스레드로 미룬다(서버 메인 스레드에서 디스크를 기다리지 않게). 읽기는 그대로 위임한다. */
public final class AsyncStore implements EvidenceStore {
    private final EvidenceStore delegate;
    private final Consumer<Throwable> onError;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anchor-mc-store");
        t.setDaemon(true);
        return t;
    });

    public AsyncStore(EvidenceStore delegate, Consumer<Throwable> onError) {
        this.delegate = delegate;
        this.onError = onError;
    }

    @Override
    public AccountRecord load(UUID id) {
        return delegate.load(id);
    }

    @Override
    public AccountRecord findByName(String name) {
        return delegate.findByName(name);
    }

    @Override
    public void save(AccountRecord record) {
        AccountRecord copy = record.copy();
        writer.execute(() -> {
            try {
                delegate.save(copy);
            } catch (Throwable t) {
                onError.accept(t);
            }
        });
    }

    @Override
    public long[] placeboTotals() {
        return delegate.placeboTotals();
    }

    @Override
    public int confirmedCount() {
        return delegate.confirmedCount();
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delegate.close();
    }
}
