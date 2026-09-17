package org.example.lawsrag.service;

/** 将口语问题规范化为更适合法律检索的查询；失败时必须降级为原问题。 */
public interface QueryRewriter {

    RewriteResult rewrite(String originalQuery);

    boolean isAvailable();

    record RewriteResult(
            String originalQuery,
            String rewrittenQuery,
            boolean changed,
            boolean fallback,
            long durationMs
    ) {
        public static RewriteResult unchanged(String query) {
            return new RewriteResult(query, query, false, false, 0L);
        }
    }
}
