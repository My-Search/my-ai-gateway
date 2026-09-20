package server

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// logStatsAggregator runs hourly to aggregate request_logs into log_stats_hourly.
type logStatsAggregator struct {
	st    *store.Store
	cache *dashCache // invalidate dash cache after aggregation
	wg    sync.WaitGroup
	stop  chan struct{}
}

func newLogStatsAggregator(st *store.Store, cache *dashCache) *logStatsAggregator {
	return &logStatsAggregator{
		st:    st,
		cache: cache,
		stop:  make(chan struct{}),
	}
}

// Start begins the hourly aggregation loop.
func (a *logStatsAggregator) Start(ctx context.Context) {
	a.wg.Add(1)
	go func() {
		defer a.wg.Done()
		slog.Info("日志预聚合任务已启动")

		// 立即执行首次聚合（可能覆盖上一小时已聚合区间，幂等）
		a.aggregateLastHour(ctx)

		ticker := time.NewTicker(1 * time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-a.stop:
				return
			case <-ticker.C:
				a.aggregateLastHour(ctx)
			}
		}
	}()
}

// Stop gracefully stops the aggregator. Safe to call multiple times.
func (a *logStatsAggregator) Stop() {
	select {
	case <-a.stop:
		// Already closed.
	default:
		close(a.stop)
	}
	a.wg.Wait()
}

// aggregateLastHour aggregates the most recent completed hour into log_stats_hourly.
func (a *logStatsAggregator) aggregateLastHour(ctx context.Context) {
	now := time.Now().UTC()
	// 聚合上一完整小时（如当前 14:23，则聚合 13:00~14:00）
	endOfHour := now.Truncate(1 * time.Hour)
	startOfHour := endOfHour.Add(-1 * time.Hour)

	hourBucket := startOfHour.Format("2006-01-02T15:04:05")

	slog.Debug("日志预聚合开始", "hour", hourBucket)

	// 使用 INSERT OR REPLACE 实现幂等
	_, err := a.st.Exec(ctx, `
		INSERT OR REPLACE INTO log_stats_hourly
			(hour_bucket, model_name, channel_name, channel_model_name, phase,
			 requests, first_byte_ms_sum, first_byte_ms_count,
			 response_time_ms_sum, completion_tokens_sum, total_tokens_sum)
		SELECT
			? AS hour_bucket,
			COALESCE(model_name, '') AS model_name,
			COALESCE(channel_name, '') AS channel_name,
			COALESCE(channel_model_name, '') AS channel_model_name,
			phase,
			COUNT(DISTINCT trace_id) AS requests,
			COALESCE(SUM(CASE WHEN first_byte_ms > 0 THEN first_byte_ms ELSE 0 END), 0) AS first_byte_ms_sum,
			COUNT(CASE WHEN first_byte_ms > 0 THEN 1 END) AS first_byte_ms_count,
			COALESCE(SUM(CASE WHEN response_time_ms > 0 THEN response_time_ms ELSE 0 END), 0) AS response_time_ms_sum,
			COALESCE(SUM(CASE WHEN phase='success' THEN COALESCE(completion_tokens, 0) ELSE 0 END), 0) AS completion_tokens_sum,
			COALESCE(SUM(CASE WHEN phase='success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) AS total_tokens_sum
		FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
		WHERE created_at >= ? AND created_at < ?
		  AND phase IN ('start', 'success')
		GROUP BY model_name, channel_name, channel_model_name, phase`,
		jtime.FormatDefault(startOfHour), jtime.FormatDefault(startOfHour), jtime.FormatDefault(endOfHour))

	if err != nil {
		slog.Warn("日志预聚合失败", "hour", hourBucket, "error", err)
		return
	}

	// 聚合完成后失效 dashboard 缓存，使下一个请求命中新的聚合数据
	if a.cache != nil {
		a.cache.Invalidate()
	}

	slog.Info("日志预聚合完成", "hour", hourBucket)
}
