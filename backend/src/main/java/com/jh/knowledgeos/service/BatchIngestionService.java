package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.InMemoryMultipartFile;
import com.jh.knowledgeos.config.BatchIngestionExecutorConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs many source files through the ingestion pipeline as one batch job.
 *
 * <p>The single-file endpoint keeps its request-thread semantics, but twenty files cannot wait
 * twenty times: the request would outlive every timeout in front of it. A batch therefore returns
 * immediately with a batch code and lets workers drain the queue, while every requested file keeps
 * the guarantee that made the single path trustworthy — its own task entry, its own events, its
 * own evidence.</p>
 *
 * <p>Failure is per file, never per batch: one unparsable document must not erase the nineteen
 * that succeeded, which is why the ledger records each outcome separately and the batch only
 * reports how they added up.</p>
 */
@Service
public class BatchIngestionService {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String CANCELED = "CANCELED";
    /** 批次父任务的目标类型：任务中心据此把重试路由回批次，而不是当成文档编号去解析。 */
    public static final String BATCH_TARGET_TYPE = "BATCH";

    private static final String ITEM_QUEUED = "QUEUED";
    private static final int ERROR_LIMIT = 500;

    private final DocumentIngestionService ingestion;
    private final TaskCenterService taskCenter;
    private final IdGenerator ids;
    private final Executor executor;
    private final int maxFiles;
    private final long maxFileBytes;
    private final int maxTracked;
    private final ConcurrentHashMap<String, BatchJob> jobs = new ConcurrentHashMap<String, BatchJob>();

    public BatchIngestionService(
            DocumentIngestionService ingestion,
            TaskCenterService taskCenter,
            IdGenerator ids,
            @Qualifier(BatchIngestionExecutorConfig.BEAN_NAME) Executor executor,
            @Value("${knowledge.batch.max-files:50}") int maxFiles,
            @Value("${knowledge.batch.max-file-mb:20}") int maxFileMb,
            @Value("${knowledge.batch.max-tracked:200}") int maxTracked) {
        this.ingestion = ingestion;
        this.taskCenter = taskCenter;
        this.ids = ids;
        this.executor = executor;
        this.maxFiles = Math.max(1, maxFiles);
        this.maxFileBytes = Math.max(1L, maxFileMb) * 1024L * 1024L;
        this.maxTracked = Math.max(10, maxTracked);
    }

    /**
     * Accepts a batch. The bytes are copied into memory before returning, because the uploaded
     * multipart files belong to the request and disappear with it: workers must not reach back
     * into a request that has already been answered.
     */
    public Map<String, Object> submit(
            Long knowledgeBaseId, List<MultipartFile> files, Double authorityScore, String permissionTags)
            throws IOException {
        if (knowledgeBaseId == null) throw new IllegalArgumentException("缺少 knowledgeBaseId");
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("请至少选择一个文件");
        if (files.size() > maxFiles) {
            throw new IllegalArgumentException("单批最多 " + maxFiles + " 个文件，当前 " + files.size() + " 个");
        }

        String batchCode = ids.next("BATCH_");
        BatchJob job = new BatchJob(batchCode, knowledgeBaseId, authorityScore, permissionTags);
        List<String> fileNames = new ArrayList<String>(files.size());
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String name = file == null ? "" : String.valueOf(file.getOriginalFilename());
            BatchItem item = new BatchItem(i, name, file == null ? 0L : file.getSize());
            if (file == null || file.isEmpty()) {
                item.status = FAILED;
                item.error = "文件内容为空";
            } else if (file.getSize() > maxFileBytes) {
                item.status = FAILED;
                item.error = "文件大小超过单批上限 " + (maxFileBytes / (1024L * 1024L)) + " MB";
            } else {
                item.file = new InMemoryMultipartFile(
                        name, file.getContentType(), file.getBytes());
            }
            fileNames.add(name);
            job.items.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("batchCode", batchCode);
        payload.put("fileCount", files.size());
        payload.put("fileNames", fileNames);
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("authorityScore", authorityScore);
        payload.put("permissionTags", permissionTags);
        payload.put("concurrencyHint", Thread.currentThread().getName());
        Map<String, Object> task = taskCenter.enqueue(knowledgeBaseId,
                TaskCenterService.TYPE_INGESTION, "批量文档接入：" + files.size() + " 个文件",
                BATCH_TARGET_TYPE, batchCode, TaskCenterService.TRIGGER_MANUAL, payload);
        job.taskCode = String.valueOf(task.get("taskCode"));
        taskCenter.start(job.taskCode, RUNNING);
        jobs.put(batchCode, job);
        evictFinished();

        run(job);
        Map<String, Object> view = status(batchCode);
        view.put("accepted", files.size());
        return view;
    }

    public Map<String, Object> status(String batchCode) {
        return render(require(batchCode));
    }

    /** Recent batches of a knowledge base, newest first, so the console can resume watching one. */
    public List<Map<String, Object>> list(Long knowledgeBaseId, Integer limit) {
        List<BatchJob> owned = new ArrayList<BatchJob>();
        for (BatchJob job : jobs.values()) {
            if (knowledgeBaseId == null || knowledgeBaseId.equals(job.knowledgeBaseId)) owned.add(job);
        }
        Collections.sort(owned, new Comparator<BatchJob>() {
            @Override
            public int compare(BatchJob left, BatchJob right) {
                return Long.compare(right.createdAt, left.createdAt);
            }
        });
        int cap = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (BatchJob job : owned) {
            if (views.size() >= cap) break;
            views.add(render(job));
        }
        return views;
    }

    /**
     * Keeps the ledger bounded. Every finished batch still holds its failed files' bytes so they
     * can be replayed, and a long-lived instance would otherwise accumulate them forever; the
     * ledger itself has already been written to the task centre and the documents to the database,
     * so dropping a finished batch loses nothing that outlives this process. Running batches are
     * never candidates.
     */
    private void evictFinished() {
        if (jobs.size() <= maxTracked) return;
        List<BatchJob> finished = new ArrayList<BatchJob>();
        for (BatchJob job : jobs.values()) {
            if (!RUNNING.equals(job.status)) finished.add(job);
        }
        Collections.sort(finished, new Comparator<BatchJob>() {
            @Override
            public int compare(BatchJob left, BatchJob right) {
                return Long.compare(left.createdAt, right.createdAt);
            }
        });
        for (BatchJob oldest : finished) {
            if (jobs.size() <= maxTracked) break;
            jobs.remove(oldest.batchCode);
        }
    }

    /**
     * Stops accepting new work for a batch. A document already being parsed runs to completion:
     * interrupting it mid-flight would leave a source half written, which is exactly the state
     * the pipeline promises never to produce.
     */
    public Map<String, Object> cancel(String batchCode) {
        BatchJob job = require(batchCode);
        if (!RUNNING.equals(job.status)) {
            throw new IllegalStateException("批次已结束，当前状态：" + job.status);
        }
        job.canceled.set(true);
        touch(job);
        return render(job);
    }

    /** Re-runs only the files that failed, so one broken document does not cost a re-import of all. */
    public Map<String, Object> retryFailed(String batchCode) {
        BatchJob job = require(batchCode);
        if (RUNNING.equals(job.status)) {
            throw new IllegalStateException("批次仍在执行中，请等待结束");
        }
        List<BatchItem> retryable = new ArrayList<BatchItem>();
        for (BatchItem item : job.items) {
            if (FAILED.equals(item.status) || CANCELED.equals(item.status)) retryable.add(item);
        }
        if (retryable.isEmpty()) throw new IllegalStateException("没有需要重跑的文件");
        for (BatchItem item : retryable) {
            if (item.file == null) {
                item.error = "原始内容已释放且未生成文档，无法重跑";
                continue;
            }
            item.status = ITEM_QUEUED;
            item.error = null;
            item.finishedAt = null;
            item.startedAt = null;
            item.attempts = item.attempts + 1;
        }
        job.status = RUNNING;
        job.canceled.set(false);
        job.finishedAt = null;
        resync(job);
        if (job.taskCode != null) {
            taskCenter.checkpoint(job.taskCode, "RETRY", progress(job), summarize(job));
        }
        run(job);
        return render(job);
    }

    // ---------------------------------------------------------------- execution

    /** Hands every queued item to the executor and settles the batch once the last one lands. */
    private void run(final BatchJob job) {
        job.status = RUNNING;
        List<CompletableFuture<Void>> futures = new ArrayList<CompletableFuture<Void>>();
        for (final BatchItem item : job.items) {
            if (!ITEM_QUEUED.equals(item.status)) continue;
            futures.add(CompletableFuture.runAsync(
                    new Runnable() {
                        @Override
                        public void run() {
                            executeItem(job, item);
                        }
                    },
                    executor));
        }
        if (futures.isEmpty()) {
            finalizeBatch(job);
            return;
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[futures.size()]));
        all.whenCompleteAsync(
                new java.util.function.BiConsumer<Void, Throwable>() {
                    @Override
                    public void accept(Void ignored, Throwable error) {
                        finalizeBatch(job);
                    }
                },
                executor);
    }

    private void executeItem(BatchJob job, BatchItem item) {
        if (job.canceled.get()) {
            item.status = CANCELED;
            item.finishedAt = System.currentTimeMillis();
            touch(job);
            return;
        }
        item.status = RUNNING;
        item.startedAt = System.currentTimeMillis();
        touch(job);
        try {
            Map<String, Object> result = ingestion.upload(
                    job.knowledgeBaseId, item.file, job.authorityScore, job.permissionTags);
            item.status = SUCCEEDED;
            item.documentId = longValue(result.get("documentId"));
            item.runId = longValue(result.get("runId"));
            item.runCode = text(result.get("runCode"));
            item.chunkCount = toInt(result.get("chunkCount"));
            item.cardCount = countOf(result.get("knowledge"), "cards");
            item.claimCount = countOf(result.get("knowledge"), "claims");
        } catch (Exception ex) {
            item.status = FAILED;
            item.error = abbreviate(rootMessage(ex), ERROR_LIMIT);
        } finally {
            item.finishedAt = System.currentTimeMillis();
            touch(job);
        }
    }

    private void finalizeBatch(BatchJob job) {
        int succeeded = 0;
        int failed = 0;
        int canceled = 0;
        for (BatchItem item : job.items) {
            if (SUCCEEDED.equals(item.status)) succeeded++;
            else if (FAILED.equals(item.status)) failed++;
            else if (CANCELED.equals(item.status)) canceled++;
        }
        job.finishedAt = System.currentTimeMillis();
        if (succeeded == 0 && failed == 0) job.status = CANCELED;
        else if (failed == 0 && canceled == 0) job.status = SUCCEEDED;
        else if (succeeded > 0) job.status = PARTIAL;
        else job.status = FAILED;

        // Failed items keep their bytes so a retry can replay them; every other item holds the
        // document id of a source already stored on disk, and releasing is what keeps a fifty
        // file batch from pinning fifty files in memory.
        for (BatchItem item : job.items) {
            if (!FAILED.equals(item.status)) item.file = null;
        }
        if (job.taskCode == null) return;
        Map<String, Object> result = summarize(job);
        if (FAILED.equals(job.status)) {
            taskCenter.fail(job.taskCode, "批量接入失败：" + failed + "/" + job.items.size() + " 个文件处理失败");
        } else if (CANCELED.equals(job.status)) {
            taskCenter.cancel(job.taskCode);
        } else {
            taskCenter.succeed(job.taskCode, result);
        }
    }

    /** Mirrors the running batch into the task ledger so watching operators see it move. */
    private void touch(BatchJob job) {
        resync(job);
        if (job.taskCode == null) return;
        try {
            taskCenter.checkpoint(job.taskCode, stage(job), progress(job), summarize(job));
        } catch (Exception ex) {
            // A ledger write must never be the reason a batch stops: progress display is
            // observability, the documents themselves are already durable.
        }
    }

    private void resync(BatchJob job) {
        int done = 0;
        for (BatchItem item : job.items) {
            if (!ITEM_QUEUED.equals(item.status) && !RUNNING.equals(item.status)) done++;
        }
        job.finished.set(done);
    }

    private String stage(BatchJob job) {
        return "已完成 " + job.finished.get() + "/" + job.items.size();
    }

    private int progress(BatchJob job) {
        if (job.items.isEmpty()) return 100;
        return (int) Math.min(100L, Math.round(job.finished.get() * 100.0 / job.items.size()));
    }

    private Map<String, Object> summarize(BatchJob job) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("batchCode", job.batchCode);
        summary.put("taskCode", job.taskCode);
        summary.put("knowledgeBaseId", job.knowledgeBaseId);
        summary.put("status", job.status);
        summary.put("fileCount", job.items.size());
        summary.put("finishedCount", job.finished.get());
        summary.put("progress", progress(job));
        return summary;
    }

    private Map<String, Object> render(BatchJob job) {
        Map<String, Object> view = summarize(job);
        view.put("succeededCount", countItems(job, SUCCEEDED));
        view.put("failedCount", countItems(job, FAILED));
        view.put("canceledCount", countItems(job, CANCELED));
        view.put("createdAt", job.createdAt);
        view.put("finishedAt", job.finishedAt);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>(job.items.size());
        for (BatchItem item : job.items) {
            items.add(item.render());
        }
        view.put("items", items);
        return view;
    }

    private int countItems(BatchJob job, String status) {
        int total = 0;
        for (BatchItem item : job.items) {
            if (status.equals(item.status)) total++;
        }
        return total;
    }

    private BatchJob require(String batchCode) {
        BatchJob job = jobs.get(batchCode);
        if (job == null) throw new IllegalArgumentException("批次不存在或已被清理：" + batchCode);
        return job;
    }

    private int countOf(Object knowledge, String key) {
        if (!(knowledge instanceof Map)) return 0;
        Object value = ((Map<?, ?>) knowledge).get(key);
        if (value instanceof List) return ((List<?>) value).size();
        if (value instanceof Number) return ((Number) value).intValue();
        Object counted = ((Map<?, ?>) knowledge).get(key + "Count");
        return counted instanceof Number ? ((Number) counted).intValue() : 0;
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return Long.valueOf(((Number) value).longValue());
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int toInt(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? 0 : parsed.intValue();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** The cause the operator needs: nested wrappers only hide it. */
    private String rootMessage(Exception ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor
                && (cursor.getMessage() == null || cursor.getMessage().trim().isEmpty())) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private String abbreviate(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static final class BatchJob {
        private final String batchCode;
        private final Long knowledgeBaseId;
        private final Double authorityScore;
        private final String permissionTags;
        private final long createdAt;
        private final List<BatchItem> items = new ArrayList<BatchItem>();
        private final AtomicInteger finished = new AtomicInteger(0);
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private volatile String taskCode;
        private volatile String status = RUNNING;
        private volatile Long finishedAt;

        private BatchJob(String batchCode, Long knowledgeBaseId, Double authorityScore, String permissionTags) {
            this.batchCode = batchCode;
            this.knowledgeBaseId = knowledgeBaseId;
            this.authorityScore = authorityScore;
            this.permissionTags = permissionTags;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static final class BatchItem {
        private final int index;
        private final String filename;
        private final long size;
        private volatile InMemoryMultipartFile file;
        private volatile String status = ITEM_QUEUED;
        private volatile String error;
        private volatile Long documentId;
        private volatile Long runId;
        private volatile String runCode;
        private volatile int chunkCount;
        private volatile int cardCount;
        private volatile int claimCount;
        private volatile int attempts = 1;
        private volatile Long startedAt;
        private volatile Long finishedAt;

        private BatchItem(int index, String filename, long size) {
            this.index = index;
            this.filename = filename;
            this.size = size;
        }

        private Map<String, Object> render() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("index", index);
            view.put("filename", filename);
            view.put("size", size);
            view.put("status", status);
            view.put("error", error);
            view.put("attempts", attempts);
            view.put("documentId", documentId);
            view.put("runId", runId);
            view.put("runCode", runCode);
            view.put("chunkCount", chunkCount);
            view.put("cardCount", cardCount);
            view.put("claimCount", claimCount);
            view.put("startedAt", startedAt);
            view.put("finishedAt", finishedAt);
            return view;
        }
    }
}
