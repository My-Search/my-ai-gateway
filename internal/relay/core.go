package relay

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/my-search/my-ai-gateway/internal/relay/logsvc"
)

// RelayCore is the candidate routing engine (Java CandidateRouter + RelayService).
type RelayCore struct {
	Store          DataStore
	RouteResolver  *RouteResolver
	LatencyTracker *LatencyTracker
	ContentMgr     *StreamContentManager
	BalancerFunc   func(string) Balancer
	MetricsFn      func(model, channel, result string, latencyMs int64)
	CircuitSkipFn  func(scope string)
	CircuitCheckFn func(ctx context.Context, candidate RoutingCandidate) (broken bool, scope string)
	CircuitTripFn  func(ctx context.Context, modelID, channelID, channelModelID int64, apiKeyID *int64)
	// TriggerProbeFn mirrors CandidateRouter.triggerProbeForCandidates.
	TriggerProbeFn func(channelID int64)
	// LastUsedFn updates channel_model.last_used_at and api_keys.last_used_at.
	ChannelModelLastUsedFn func(ctx context.Context, channelModelID int64)
	GatewayKeyLastUsedFn   func(ctx context.Context, authHeader string)
	// RateLimitFn / usage estimation guards remain internal.
	LogWriter *logsvc.LogWriter

	// PreprocessDeps supplies prompt-injection / model-flag lookups.
	PreprocessDeps PreprocessDeps

	httpClient *http.Client

	mu               sync.Mutex
	streamUsage      map[string][3]int
	streamTranslate  *StreamTranslateStates
	firstByteArrival sync.Map // traceID -> int64 (unix millis)
}

// NewRelayCore creates the engine.
func NewRelayCore(store DataStore) *RelayCore {
	return &RelayCore{
		Store:           store,
		RouteResolver:   NewRouteResolver(store),
		LatencyTracker:  NewLatencyTrackerFixed(DefaultMinTimeoutMs, DefaultMaxTimeoutMs),
		ContentMgr:      NewStreamContentManager(),
		BalancerFunc:    NewBalancerFactory(),
		streamUsage:     map[string][3]int{},
		streamTranslate: NewStreamTranslateStates(),
		// No client-level timeout: the Java build applied a per-first-byte timeout
		// (streams) and a per-attempt timeout (non-stream) via Reactor, never a
		// whole-body cap. Timeouts are enforced per attempt below.
		httpClient: &http.Client{
			Transport: &http.Transport{
				MaxIdleConns:        100,
				MaxIdleConnsPerHost: 10,
				IdleConnTimeout:     30 * time.Second,
				TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
			},
		},
	}
}

// ---------------------------------------------------------------------------
// Non-stream relay (Java CandidateRouter.executeRelay + tryCandidates)
// ---------------------------------------------------------------------------

// RelayResult is the non-stream outcome: body plus the HTTP status to return.
type RelayResult struct {
	Body         string
	StatusCode   int
	Interrupted  bool
	ClientFormat string
}

// RelayNonStream runs the non-stream routing loop.
func (c *RelayCore) RelayNonStream(ctx context.Context, req *InternalRequest, authHeader, headersJSON, rawBody string) RelayResult {
	traceID := genTraceID()
	startTime := time.Now()
	clientFormat := req.ClientAPIFormat

	gwKeyID, _ := validateGatewayKey(ctx, c.Store, authHeader)
	c.LogTraceStart(ctx, traceID, req.Model, gwKeyID, headersJSON, rawBody)
	if gwKeyID == 0 {
		if c.LogWriter != nil {
			c.LogWriter.WriteFail(ctx, traceID, req.Model, 0, "auth", "无效或缺失的 API Key", 0, nil, 0)
		}
		return RelayResult{Body: BuildErrorBody(clientFormat, "无效或缺失的 API Key", "authentication_error", 401), StatusCode: 401}
	}

	// Java RelayService.relayNonStream applies the preprocessor chain here.
	ApplyPromptInjections(ctx, c.PreprocessDeps, req)
	PreprocessMediaInvalidation(ctx, c.PreprocessDeps, req)
	ApplyReasoningEffortOverride(ctx, c.PreprocessDeps, req)

	routingCtx := c.RouteResolver.ResolveModelRouting(ctx, req.Model)
	candidates := c.RouteResolver.BuildCandidates(ctx, req)
	c.triggerProbeForCandidates(candidates)
	if routingCtx.ModelID == 0 || len(candidates) == 0 {
		if c.LogWriter != nil {
			c.LogWriter.WriteFail(ctx, traceID, req.Model, gwKeyID, "error", "没有可用的路由候选", 0, nil, 0)
		}
		return RelayResult{Body: BuildErrorBody(clientFormat,
			"没有可用的路由候选（关联的渠道/API Key/模型均不可用）", "api_error", 503), StatusCode: 503}
	}

	// Java applies a 600s ceiling over the whole candidate loop.
	deadline := startTime.Add(MaxTotalTimeoutMs * time.Millisecond)
	if d, ok := ctx.Deadline(); !ok || d.After(deadline) {
		var cancel context.CancelFunc
		ctx, cancel = context.WithDeadline(ctx, deadline)
		defer cancel()
	}

	balancer := c.BalancerFunc(routingCtx.Strategy)
	retryIndex := 0
	var lastErrMsg string
	remaining := append([]RoutingCandidate(nil), candidates...)

	for len(remaining) > 0 {
		candidate := balancer.Select(remaining, routingCtx.ModelID)
		if candidate == nil {
			break
		}

		if scope := c.circuitBreakScope(ctx, *candidate); scope != "" {
			slog.Info("已熔断跳过", "channel", candidate.ChannelName, "model", candidate.ModelName,
				"key", candidate.APIKeyName, "scope", scope, "retryIndex", retryIndex)
			if c.CircuitSkipFn != nil {
				c.CircuitSkipFn(scope)
			}
			c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseSkip,
				scope+"跳过 "+candidateLabel(*candidate), retryIndex, nil, nil)
			remaining = removeCandidate(remaining, candidate)
			retryIndex++
			continue
		}

		if unsupported := UnsupportedMediaTypes(req, candidate.Input); len(unsupported) > 0 {
			c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseSkip,
				"当前模型不支持请求中含有的这些类型："+strings.Join(unsupported, "、")+" 因此跳过 "+candidateLabel(*candidate), retryIndex, nil, nil)
			remaining = removeCandidate(remaining, candidate)
			retryIndex++
			continue
		}

		slog.Info("路由决策", "channel", candidate.ChannelName, "model", candidate.ModelName,
			"key", candidate.APIKeyName, "retryIndex", retryIndex)

		effort := resolveEffectiveEffort(req, *candidate)
		c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseStart,
			"路由到 "+candidateLabel(*candidate), retryIndex, effort, nil)

		provider := c.resolveProvider(*candidate, req.ClientAPIFormat)
		maxAttempts := routingCtx.MaxAttempts

		body, firstByteMs, err := c.invokeCandidateWithRetries(ctx, traceID, gwKeyID, req, *candidate, provider,
			retryIndex, maxAttempts, authHeader, false)
		if err != nil {
			var nre *NonRetryableProviderError
			if errors.As(err, &nre) {
				slog.Warn("候选返回400，不触发熔断，直接重路由", "channel", candidate.ChannelName)
				c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseSkip,
					"400错误跳过 "+candidateLabel(*candidate)+" 原因: "+err.Error(), retryIndex, nil, nil)
				remaining = removeCandidate(remaining, candidate)
				lastErrMsg = err.Error()
				retryIndex++
				continue
			}
			slog.Warn("候选失败（重试耗尽）", "channel", candidate.ChannelName, "error", err)
			c.handleFailure(ctx, req, *candidate)
			balancer.MarkFailed(candidate)
			remaining = removeCandidate(remaining, candidate)
			lastErrMsg = err.Error()
			c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseSkip,
				"重试耗尽跳过 "+candidateLabel(*candidate)+" 原因: "+err.Error(), retryIndex, nil, nil)
			retryIndex++
			continue
		}

		elapsed := time.Since(startTime).Milliseconds()
		balancer.MarkSuccess(candidate)
		c.markLastUsed(ctx, *candidate, authHeader)
		c.recordLatency(*candidate, firstByteMs, elapsed)
		if c.MetricsFn != nil {
			c.MetricsFn(req.Model, candidate.ChannelName, "success", elapsed)
		}

		transformed := TransformResponse(body, provider, clientFormat, req.Model)
		pt, ct, tt := extractUsageFromResponse(body)
		if c.LogWriter != nil {
			c.LogWriter.WriteSuccess(ctx, traceID, candidate.APIKeyName, req.Model,
				candidate.ModelName, candidate.ChannelName, gwKeyID,
				"请求成功", elapsed, firstByteMs, retryIndex, pt, ct, tt)
		}
		return RelayResult{Body: transformed, StatusCode: 200, ClientFormat: clientFormat}
	}

	failMsg := buildFailMessage(lastErrMsg)
	elapsed := time.Since(startTime).Milliseconds()
	if c.LogWriter != nil {
		c.LogWriter.WriteFail(ctx, traceID, req.Model, gwKeyID, "error", failMsg, elapsed, nil, retryIndex)
	}
	if c.MetricsFn != nil {
		c.MetricsFn(req.Model, "", "fail", elapsed)
	}
	return RelayResult{Body: BuildErrorBody(clientFormat, failMsg, "api_error", 503), StatusCode: 503, ClientFormat: clientFormat}
}

// invokeCandidateWithRetries mirrors CandidateRouter.invokeCandidateWithRetries.
// It returns the (already provider-formatted) body and the first-byte latency.
func (c *RelayCore) invokeCandidateWithRetries(ctx context.Context, traceID string, gwKeyID int64,
	req *InternalRequest, candidate RoutingCandidate, provider string,
	retryIndex, maxAttempts int, authHeader string, stream bool) (string, *int64, error) {

	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		attemptStart := time.Now()
		timeoutMs := c.LatencyTracker.GetTimeout(candidate.ChannelID, candidate.ChannelModelID)
		if timeoutMs < NonStreamMinTimeoutMs {
			timeoutMs = NonStreamMinTimeoutMs
		}
		c.firstByteArrival.Delete(traceID)

		attemptCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
		body, status, fbMs, err := c.callProvider(ctx, attemptCtx, req, candidate, provider, traceID)
		cancel()

		if err == nil && strings.TrimSpace(body) == "" {
			slog.Warn("候选返回空响应", "channel", candidate.ChannelName, "model", candidate.ModelName,
				"attempt", attempt, "maxAttempts", maxAttempts)
			err = errors.New("Provider returned empty response, treated as timeout")
		}
		if err == nil {
			if strings.TrimSpace(body) == "" {
				err = errors.New("Provider returned empty response, treated as timeout")
			} else {
				return body, fbMs, nil
			}
		}

		_ = status
		attemptDuration := time.Since(attemptStart).Milliseconds()
		c.LatencyTracker.RecordTimeout(candidate.ChannelID, candidate.ChannelModelID, timeoutMs)
		slog.Warn("候选尝试失败", "attempt", attempt, "maxAttempts", maxAttempts,
			"channel", candidate.ChannelName, "key", candidate.APIKeyName,
			"model", candidate.ModelName, "elapsedMs", attemptDuration, "error", err)

		var nre *NonRetryableProviderError
		if errors.As(err, &nre) {
			return "", nil, err
		}
		if attempt < maxAttempts {
			c.logPhase(ctx, traceID, gwKeyID, candidate, req, PhaseRetry,
				fmt.Sprintf("第 %d 次失败: %s，准备第 %d 次重试", attempt, err.Error(), attempt+1),
				retryIndex, nil, &attemptDuration)
			lastErr = err
			continue
		}
		return "", nil, err
	}
	if lastErr != nil {
		return "", nil, lastErr
	}
	return "", nil, errors.New("max attempts reached")
}

// callProvider performs one upstream non-stream call, returning body, status and
// the first-byte latency in ms (measured when the first body byte arrives).
func (c *RelayCore) callProvider(ctx context.Context, attemptCtx context.Context,
	req *InternalRequest, candidate RoutingCandidate, provider, traceID string) (string, int, *int64, error) {

	upstreamBody := BuildProviderRequest(ReqForCandidate(req, candidate), provider)
	endpoint := buildProviderURL(candidate, provider)
	headers := buildProviderHeaders(candidate, provider)

	started := time.Now()
	httpReq, err := http.NewRequestWithContext(attemptCtx, http.MethodPost, endpoint, bytes.NewReader([]byte(upstreamBody)))
	if err != nil {
		return "", 0, nil, err
	}
	for k, v := range headers {
		httpReq.Header.Set(k, v)
	}

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return "", 0, nil, err
	}
	defer resp.Body.Close()

	var firstByte *int64
	buf := make([]byte, 0, 4096)
	readBuf := make([]byte, 4096)
	for {
		n, readErr := resp.Body.Read(readBuf)
		if n > 0 {
			if firstByte == nil {
				fb := time.Since(started).Milliseconds()
				firstByte = &fb
			}
			buf = append(buf, readBuf[:n]...)
		}
		if readErr != nil {
			if readErr == io.EOF {
				break
			}
			return "", resp.StatusCode, firstByte, readErr
		}
	}

	if resp.StatusCode == 400 {
		return "", resp.StatusCode, firstByte, NewNonRetryableError(400, string(buf))
	}
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return string(buf), resp.StatusCode, firstByte, nil
	}
	return "", resp.StatusCode, firstByte,
		fmt.Errorf("Provider error: %d body: %s", resp.StatusCode, string(buf))
}

// ---------------------------------------------------------------------------
// Stream relay (Java CandidateRouter.executeStreamRelay + tryStreamCandidates)
// ---------------------------------------------------------------------------

// StreamSink receives translated client events.
type StreamSink struct {
	OnEvent func(event, data string)
	OnDone  func()
	OnError func(err error)
}

// RelayStream runs the streaming routing loop.
func (c *RelayCore) RelayStream(ctx context.Context, req *InternalRequest, authHeader, headersJSON, rawBody string,
	internalClient bool, sink StreamSink) {

	traceID := genTraceID()
	startTime := time.Now()
	clientFormat := req.ClientAPIFormat

	gwKeyID, _ := validateGatewayKey(ctx, c.Store, authHeader)
	c.LogTraceStart(ctx, traceID, req.Model, gwKeyID, headersJSON, rawBody)
	if gwKeyID == 0 {
		if c.LogWriter != nil {
			c.LogWriter.WriteFail(ctx, traceID, req.Model, 0, "auth", "无效或缺失的 API Key", 0, nil, 0)
		}
		if sink.OnError != nil {
			sink.OnError(errors.New("无效或缺失的 API Key"))
		}
		return
	}

	ApplyPromptInjections(ctx, c.PreprocessDeps, req)
	PreprocessMediaInvalidation(ctx, c.PreprocessDeps, req)
	ApplyReasoningEffortOverride(ctx, c.PreprocessDeps, req)

	routingCtx := c.RouteResolver.ResolveModelRouting(ctx, req.Model)
	candidates := c.RouteResolver.BuildCandidates(ctx, req)
	c.triggerProbeForCandidates(candidates)
	if routingCtx.ModelID == 0 || len(candidates) == 0 {
		if c.LogWriter != nil {
			c.LogWriter.WriteFail(ctx, traceID, req.Model, gwKeyID, "error", "没有可用的路由候选", 0, nil, 0)
		}
		if sink.OnError != nil {
			sink.OnError(errors.New("没有可用的路由候选"))
		}
		return
	}

	balancer := c.BalancerFunc(routingCtx.Strategy)
	retryIndex := 0
	var lastErrMsg string
	remaining := append([]RoutingCandidate(nil), candidates...)
	currentReq := req
	finalLogged := false

	for len(remaining) > 0 {
		candidate := balancer.Select(remaining, routingCtx.ModelID)
		if candidate == nil {
			break
		}

		if scope := c.circuitBreakScope(ctx, *candidate); scope != "" {
			slog.Info("已熔断跳过", "channel", candidate.ChannelName, "model", candidate.ModelName,
				"key", candidate.APIKeyName, "scope", scope, "retryIndex", retryIndex)
			if c.CircuitSkipFn != nil {
				c.CircuitSkipFn(scope)
			}
			c.logPhase(ctx, traceID, gwKeyID, *candidate, currentReq, PhaseSkip,
				scope+"跳过 "+candidateLabel(*candidate), retryIndex, nil, nil)
			remaining = removeCandidate(remaining, candidate)
			retryIndex++
			continue
		}

		if unsupported := UnsupportedMediaTypes(currentReq, candidate.Input); len(unsupported) > 0 {
			c.logPhase(ctx, traceID, gwKeyID, *candidate, currentReq, PhaseSkip,
				"当前模型不支持请求中含有的这些类型："+strings.Join(unsupported, "、")+" 因此跳过 "+candidateLabel(*candidate), retryIndex, nil, nil)
			remaining = removeCandidate(remaining, candidate)
			retryIndex++
			continue
		}

		slog.Info("流式路由决策", "channel", candidate.ChannelName, "model", candidate.ModelName,
			"key", candidate.APIKeyName, "retryIndex", retryIndex)

		effort := resolveEffectiveEffort(currentReq, *candidate)
		c.logPhase(ctx, traceID, gwKeyID, *candidate, currentReq, PhaseStart,
			"流式路由到 "+candidateLabel(*candidate), retryIndex, effort, nil)

		provider := c.resolveProvider(*candidate, req.ClientAPIFormat)
		if internalClient && sink.OnEvent != nil {
			sink.OnEvent("", BuildRoutingProgressJSON("trying", candidate.ChannelType,
				candidate.ChannelName, candidate.APIKeyName, candidate.ModelName, retryIndex, ""))
		}

		// The translator state lives for the whole trace across candidates.
		state := c.streamTranslate.GetOrCreate(traceID, provider, clientFormat)
		acc := &streamAccumulator{}
		firstByte := c.streamCandidateLoop(ctx, traceID, gwKeyID, currentReq, *candidate, provider,
			routingCtx.MaxAttempts, authHeader, internalClient, clientFormat, state, acc, sink, startTime)

		if firstByte.err == nil {
			elapsed := time.Since(startTime).Milliseconds()
			balancer.MarkSuccess(candidate)
			c.markLastUsed(ctx, *candidate, authHeader)
			c.recordLatency(*candidate, firstByte.firstByteMs, elapsed)

			pt, ct, tt := acc.promptTokens, acc.completionTokens, acc.totalTokens
			resultMsg := "流式请求成功"
			if pt == 0 && ct == 0 && tt == 0 {
				if content := acc.content.String(); content != "" {
					ct = max(1, len([]rune(content))/2)
					tt = ct
					resultMsg = "流式请求成功（用量为估算值）"
				}
			}
			if c.LogWriter != nil {
				c.LogWriter.WriteSuccess(ctx, traceID, candidate.APIKeyName, currentReq.Model,
					candidate.ModelName, candidate.ChannelName, gwKeyID,
					resultMsg, elapsed, firstByte.firstByteMs, retryIndex, pt, ct, tt)
			}
			if c.MetricsFn != nil {
				c.MetricsFn(currentReq.Model, candidate.ChannelName, "success", elapsed)
			}
			c.ContentMgr.Clear(traceID)
			c.streamTranslate.Clear(traceID)
			finalLogged = true
			if sink.OnDone != nil {
				sink.OnDone()
			}
			return
		}

		// Failure of this candidate: splice accumulated content into the request.
		accumulated := c.ContentMgr.GetAndClear(traceID)
		if accumulated != "" {
			currentReq = BuildRequestWithContext(req, accumulated)
		}

		var nre *NonRetryableProviderError
		if errors.As(firstByte.err, &nre) {
			slog.Warn("流式候选返回400，不触发熔断，直接重路由", "channel", candidate.ChannelName)
			c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseSkip,
				"400错误跳过 "+candidateLabel(*candidate)+" 原因: "+firstByte.err.Error(), retryIndex, nil, nil)
			remaining = removeCandidate(remaining, candidate)
			lastErrMsg = firstByte.err.Error()
			retryIndex++
			if internalClient && sink.OnEvent != nil {
				sink.OnEvent("", BuildRoutingProgressJSON("switching", candidate.ChannelType,
					candidate.ChannelName, candidate.APIKeyName, candidate.ModelName, retryIndex, firstByte.err.Error()))
			}
			continue
		}

		slog.Warn("流式候选失败（重试耗尽）", "channel", candidate.ChannelName, "error", firstByte.err)
		c.handleFailure(ctx, currentReq, *candidate)
		balancer.MarkFailed(candidate)
		remaining = removeCandidate(remaining, candidate)
		lastErrMsg = firstByte.err.Error()
		c.logPhase(ctx, traceID, gwKeyID, *candidate, req, PhaseSkip,
			"重试耗尽跳过 "+candidateLabel(*candidate)+" 原因: "+firstByte.err.Error(), retryIndex, nil, nil)
		retryIndex++
		if internalClient && sink.OnEvent != nil {
			sink.OnEvent("", BuildRoutingProgressJSON("switching", candidate.ChannelType,
				candidate.ChannelName, candidate.APIKeyName, candidate.ModelName, retryIndex, firstByte.err.Error()))
		}
	}

	failMsg := buildFailMessage(lastErrMsg)
	c.ContentMgr.Clear(traceID)
	c.streamTranslate.Clear(traceID)
	if c.LogWriter != nil {
		c.LogWriter.WriteFail(ctx, traceID, req.Model, gwKeyID, "error", failMsg,
			time.Since(startTime).Milliseconds(), nil, retryIndex)
	}
	if c.MetricsFn != nil {
		c.MetricsFn(req.Model, "", "fail", time.Since(startTime).Milliseconds())
	}
	if !finalLogged && sink.OnError != nil {
		sink.OnError(errors.New(failMsg))
	}
}

type streamResult struct {
	err         error
	firstByteMs *int64
}

type streamAccumulator struct {
	content          strings.Builder
	promptTokens     int
	completionTokens int
	totalTokens      int
}

// streamCandidateLoop runs one candidate's attempts, converting and forwarding
// events to the client through the sink.
func (c *RelayCore) streamCandidateLoop(ctx context.Context, traceID string, gwKeyID int64,
	req *InternalRequest, candidate RoutingCandidate, provider string,
	maxAttempts int, authHeader string, internalClient bool, clientFormat string,
	state StreamTranslateState, acc *streamAccumulator, sink StreamSink, startTime time.Time) streamResult {

	var lastErr error
	var firstByteMs *int64

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		attemptStart := time.Now()
		timeoutMs := c.LatencyTracker.GetTimeout(candidate.ChannelID, candidate.ChannelModelID)
		c.firstByteArrival.Delete(traceID)
		currentReq := req

		err, fb := c.callProviderStream(ctx, req, candidate, provider, timeoutMs, internalClient,
			clientFormat, state, acc, sink, traceID, startTime)
		if fb != nil {
			firstByteMs = fb
		}
		if err == nil {
			return streamResult{firstByteMs: firstByteMs}
		}
		lastErr = err

		var nre *NonRetryableProviderError
		if errors.As(err, &nre) {
			return streamResult{err: err, firstByteMs: firstByteMs}
		}
		attemptDuration := time.Since(attemptStart).Milliseconds()
		c.LatencyTracker.RecordTimeout(candidate.ChannelID, candidate.ChannelModelID, timeoutMs)
		if attempt < maxAttempts {
			retryReq := currentReq
			if content := acc.content.String(); content != "" {
				retryReq = BuildRequestWithContext(req, content)
			}
			c.logPhase(ctx, traceID, gwKeyID, candidate, retryReq, PhaseRetry,
				fmt.Sprintf("第 %d 次失败: %s，准备第 %d 次重试", attempt, err.Error(), attempt+1),
				0, nil, &attemptDuration)
			if internalClient && sink.OnEvent != nil {
				sink.OnEvent("", BuildRoutingProgressJSON("retrying", candidate.ChannelType,
					candidate.ChannelName, candidate.APIKeyName, candidate.ModelName, 0,
					fmt.Sprintf("第%d次失败，第%d次重试", attempt, attempt+1)))
			}
		}
	}
	return streamResult{err: lastErr, firstByteMs: firstByteMs}
}

// callProviderStream performs one upstream streaming call and forwards translated
// events. A per-first-event timeout is applied (Java's firstTimeout); after the
// first event the stream is no longer time-limited.
func (c *RelayCore) callProviderStream(ctx context.Context, req *InternalRequest, candidate RoutingCandidate,
	provider string, timeoutMs int64, internalClient bool, clientFormat string,
	state StreamTranslateState, acc *streamAccumulator, sink StreamSink, traceID string,
	startTime time.Time) (error, *int64) {

	upstreamBody := BuildProviderRequest(ReqForCandidate(req, candidate), provider)
	endpoint := buildProviderURL(candidate, provider)
	headers := buildProviderHeaders(candidate, provider)

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader([]byte(upstreamBody)))
	if err != nil {
		return err, nil
	}
	for k, v := range headers {
		httpReq.Header.Set(k, v)
	}

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return err, nil
	}
	defer resp.Body.Close()

	if resp.StatusCode == 400 {
		body, _ := io.ReadAll(resp.Body)
		return NewNonRetryableError(400, string(body)), nil
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("Provider stream error: %d body: %s", resp.StatusCode, string(body)), nil
	}

	if internalClient && sink.OnEvent != nil {
		sink.OnEvent("", BuildGatewayMetaJSON(candidate.ChannelType, candidate.ChannelName,
			candidate.APIKeyName, candidate.ModelName))
	}

	var firstByte *int64
	sawEvent := false
	reader := resp.Body
	buf := make([]byte, 0, 4096)
	readBuf := make([]byte, 4096)
	timeout := time.NewTimer(time.Duration(timeoutMs) * time.Millisecond)
	defer timeout.Stop()

	for {
		type readResult struct {
			n   int
			err error
		}
		resCh := make(chan readResult, 1)
		go func() {
			n, err := reader.Read(readBuf)
			resCh <- readResult{n, err}
		}()
		var n int
		var readErr error
		if !sawEvent {
			select {
			case rr := <-resCh:
				n, readErr = rr.n, rr.err
			case <-timeout.C:
				return errors.New("did not observe any item within timeout"), firstByte
			case <-ctx.Done():
				return ctx.Err(), firstByte
			}
		} else {
			select {
			case rr := <-resCh:
				n, readErr = rr.n, rr.err
			case <-ctx.Done():
				return ctx.Err(), firstByte
			}
		}

		if n > 0 {
			if firstByte == nil {
				fb := time.Since(startTime).Milliseconds()
				firstByte = &fb
				c.LatencyTracker.Record(candidate.ChannelID, candidate.ChannelModelID, fb)
			}
			sawEvent = true
			buf = append(buf, readBuf[:n]...)
			for {
				idx := bytes.Index(buf, []byte("\n\n"))
				if idx < 0 {
					break
				}
				block := string(buf[:idx])
				buf = buf[idx+2:]
				c.dispatchSseBlock(block, provider, clientFormat, req.Model, state, acc, sink, traceID)
			}
		}
		if readErr != nil {
			if readErr == io.EOF {
				// Flush translator terminator (Java translateStreamEnd).
				if clientFormat != provider {
					if flushed := TranslateStreamEnd(state, provider, req.Model); len(flushed) > 0 {
						for _, payload := range flushed {
							addTranslated(sink, payload)
						}
					}
				}
				if !sawEvent {
					return errors.New("流式候选返回空响应"), firstByte
				}
				return nil, firstByte
			}
			return readErr, firstByte
		}
	}
}

// dispatchSseBlock parses one raw SSE block and forwards each event.
func (c *RelayCore) dispatchSseBlock(block, provider, clientFormat, originalModel string,
	state StreamTranslateState, acc *streamAccumulator, sink StreamSink, traceID string) {

	events := ParseSseEventBlock(block)
	for _, ev := range events {
		if ev.Data == "[DONE]" {
			if provider != clientFormat {
				if flushed := TranslateStreamEnd(state, provider, originalModel); len(flushed) > 0 {
					for _, payload := range flushed {
						addTranslated(sink, payload)
					}
				}
			}
			continue
		}
		// Accumulate raw text content for reroute context splicing.
		if text := ExtractTextContentFromRawData(ev.Data, provider); text != "" {
			c.ContentMgr.Append(traceID, text)
			acc.content.WriteString(text)
		}
		if pt, ct, tt, ok := ExtractUsageFromSseData(ev.Data); ok {
			acc.promptTokens, acc.completionTokens, acc.totalTokens = pt, ct, tt
		}
		if provider == clientFormat {
			payload := ReplaceModelInJSON(ev.Data, originalModel)
			if sink.OnEvent != nil {
				sink.OnEvent(ev.Event, payload)
			}
			continue
		}
		translated := TranslateStreamEvent(state, provider, ev.Event, ev.Data, originalModel)
		for _, payload := range translated {
			addTranslated(sink, payload)
		}
	}
}

// addTranslated splits translated payloads on newlines and forwards them.
func addTranslated(sink StreamSink, payload string) {
	var events []SseEvent
	events = AddSseEventSplit(events, "", payload)
	for _, ev := range events {
		if sink.OnEvent != nil {
			sink.OnEvent(ev.Event, ev.Data)
		}
	}
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

// ReqForCandidate returns a copy of req with the channel model name and the
// relation's default reasoning effort applied (Java buildProviderRequestBody).
func ReqForCandidate(req *InternalRequest, candidate RoutingCandidate) *InternalRequest {
	cp := *req
	cp.Model = candidate.ModelName
	if cp.ReasoningEffort == nil || *cp.ReasoningEffort == "" {
		cp.ReasoningEffort = candidate.ReasoningEffort
	}
	return &cp
}

func resolveEffectiveEffort(req *InternalRequest, candidate RoutingCandidate) *string {
	if req.ReasoningEffort != nil && *req.ReasoningEffort != "" {
		return req.ReasoningEffort
	}
	return candidate.ReasoningEffort
}

func candidateLabel(c RoutingCandidate) string {
	return c.ChannelName + "/" + c.APIKeyName + "/" + c.ModelName
}

func (c *RelayCore) resolveProvider(candidate RoutingCandidate, defaultProvider string) string {
	if strings.TrimSpace(candidate.ChannelType) != "" {
		return candidate.ChannelType
	}
	return defaultProvider
}

// circuitBreakScope mirrors CandidateRouter.circuitBreakScope, returning the
// scope description or "" when healthy.
func (c *RelayCore) circuitBreakScope(ctx context.Context, candidate RoutingCandidate) string {
	if c.CircuitCheckFn == nil {
		return ""
	}
	broken, scope := c.CircuitCheckFn(ctx, candidate)
	if !broken {
		return ""
	}
	return scope
}

// handleFailure mirrors CandidateRouter.handleFailure.
func (c *RelayCore) handleFailure(ctx context.Context, req *InternalRequest, candidate RoutingCandidate) {
	if c.CircuitTripFn == nil {
		return
	}
	modelID := c.RouteResolver.ResolveModelID(ctx, req.Model)
	if modelID == 0 {
		return
	}
	apiKeyID := candidate.APIKeyID
	c.CircuitTripFn(ctx, modelID, candidate.ChannelID, candidate.ChannelModelID, &apiKeyID)
}

// triggerProbeForCandidates mirrors CandidateRouter.triggerProbeForCandidates.
func (c *RelayCore) triggerProbeForCandidates(candidates []RoutingCandidate) {
	if c.TriggerProbeFn == nil {
		return
	}
	seen := map[int64]bool{}
	for _, cand := range candidates {
		if cand.ChannelID == 0 || seen[cand.ChannelID] {
			continue
		}
		seen[cand.ChannelID] = true
		c.TriggerProbeFn(cand.ChannelID)
	}
}

// markLastUsed updates channel_model / api_key last_used_at like RelayLogger and
// ModelService do on every successful candidate.
func (c *RelayCore) markLastUsed(ctx context.Context, candidate RoutingCandidate, authHeader string) {
	if c.ChannelModelLastUsedFn != nil {
		c.ChannelModelLastUsedFn(ctx, candidate.ChannelModelID)
	}
	if c.GatewayKeyLastUsedFn != nil {
		c.GatewayKeyLastUsedFn(ctx, authHeader)
	}
}

func (c *RelayCore) recordLatency(candidate RoutingCandidate, firstByteMs *int64, elapsed int64) {
	if firstByteMs != nil {
		c.LatencyTracker.Record(candidate.ChannelID, candidate.ChannelModelID, *firstByteMs)
		return
	}
	c.LatencyTracker.Record(candidate.ChannelID, candidate.ChannelModelID, elapsed)
}

// logPhase writes one candidate-phase log row (Java RelayLogger.logPhase).
func (c *RelayCore) logPhase(ctx context.Context, traceID string, gwKeyID int64, candidate RoutingCandidate,
	req *InternalRequest, phase, message string, retryIndex int, effort *string, attemptMs *int64) {
	if c.LogWriter == nil {
		return
	}
	effortStr := ""
	if effort != nil {
		effortStr = *effort
	}
	c.LogWriter.WriteCandidatePhase(ctx, traceID, candidate.APIKeyName, req.Model,
		candidate.ModelName, candidate.ChannelName, gwKeyID,
		phase, StatusPending, message, retryIndex, attemptMs, effortStr)
}

// LogTraceStart writes the "start" row carrying the raw request.
func (c *RelayCore) LogTraceStart(ctx context.Context, traceID, modelName string, gwKeyID int64, headersJSON, rawBody string) {
	if c.LogWriter == nil {
		return
	}
	c.LogWriter.WriteStart(ctx, traceID, modelName, gwKeyID, headersJSON, rawBody,
		extractReasoningEffortFromBody(rawBody))
}

// extractUsageFromResponse parses token usage from a non-stream response body,
// supporting both OpenAI and Anthropic field names (Java
// CandidateRouter.extractUsageFromProviderResponse).
func extractUsageFromResponse(body string) (promptTokens, completionTokens, totalTokens int) {
	var resp struct {
		Usage map[string]any `json:"usage"`
	}
	if err := json.Unmarshal([]byte(body), &resp); err != nil || resp.Usage == nil {
		return 0, 0, 0
	}
	usage := resp.Usage
	pt, hasPT := usage["prompt_tokens"]
	if hasPT {
		promptTokens = intVal(pt)
	} else {
		promptTokens = intVal(usage["input_tokens"])
	}
	ct, hasCT := usage["completion_tokens"]
	if hasCT {
		completionTokens = intVal(ct)
	} else {
		completionTokens = intVal(usage["output_tokens"])
	}
	if tt, hasTT := usage["total_tokens"]; hasTT {
		totalTokens = intVal(tt)
	} else {
		totalTokens = promptTokens + completionTokens
	}
	return promptTokens, completionTokens, totalTokens
}

// extractReasoningEffortFromBody is defined in extract_usage.go.

func validateGatewayKey(ctx context.Context, store DataStore, authHeader string) (int64, string) {
	key := strings.TrimSpace(authHeader)
	if strings.HasPrefix(key, "Bearer ") {
		key = strings.TrimSpace(key[7:])
	}
	if key == "" {
		return 0, ""
	}
	row, err := store.QueryOne(ctx,
		"SELECT id, key_name FROM api_keys WHERE key_value = ? AND enabled = 1", key)
	if err == nil {
		return row.I64("id", 0), row.Str("key_name")
	}
	return 0, ""
}

func removeCandidate(candidates []RoutingCandidate, c *RoutingCandidate) []RoutingCandidate {
	for i := range candidates {
		if &candidates[i] == c {
			return append(candidates[:i], candidates[i+1:]...)
		}
	}
	return candidates
}

func buildFailMessage(msg string) string {
	if strings.TrimSpace(msg) == "" {
		return "所有候选均失败"
	}
	lower := strings.ToLower(msg)
	if strings.Contains(lower, "timeout") || strings.Contains(lower, "did not observe") ||
		strings.Contains(lower, "read timed out") || strings.Contains(lower, "connect timed out") {
		return "请求超时"
	}
	if strings.HasPrefix(msg, "Provider error:") {
		if bodyIdx := strings.Index(msg, "body: "); bodyIdx > 0 {
			body := msg[bodyIdx+6:]
			var parsed map[string]any
			if json.Unmarshal([]byte(body), &parsed) == nil {
				if errNode, ok := parsed["error"]; ok {
					switch e := errNode.(type) {
					case map[string]any:
						message := strVal(e["message"])
						if message != "" {
							if t := strVal(e["type"]); t != "" && t != "null" {
								return "[" + t + "] " + message
							}
							return message
						}
					case string:
						return e
					}
				}
			}
			return strings.TrimSpace(msg[len("Provider error:"):bodyIdx]) + " " + body
		}
		return msg
	}
	if strings.Contains(lower, "empty response") || strings.Contains(lower, "treated as timeout") {
		return "请求超时"
	}
	return msg
}

func buildProviderURL(candidate RoutingCandidate, provider string) string {
	baseURL := strings.TrimRight(strings.TrimSpace(candidate.BaseURL), "/")
	if baseURL == "" {
		if provider == ProtoAnthropic {
			baseURL = "https://api.anthropic.com/v1"
		} else {
			baseURL = "https://api.openai.com/v1"
		}
	}
	if provider == ProtoAzure {
		return baseURL
	}
	if provider == ProtoAnthropic {
		return baseURL + "/messages"
	}
	return baseURL + "/chat/completions"
}

func buildProviderHeaders(candidate RoutingCandidate, provider string) map[string]string {
	h := map[string]string{"Content-Type": "application/json"}
	ak := strings.TrimSpace(candidate.APIKey)
	switch provider {
	case ProtoAzure:
		h["api-key"] = ak
	case ProtoAnthropic:
		h["x-api-key"] = ak
		h["anthropic-version"] = "2023-06-01"
	default:
		h["Authorization"] = "Bearer " + ak
	}
	if candidate.CustomHeaders != "" {
		var ch map[string]string
		if json.Unmarshal([]byte(candidate.CustomHeaders), &ch) == nil {
			for k, v := range ch {
				h[k] = v
			}
		}
	}
	return h
}

// FormatSSE renders one SSE frame.
func FormatSSE(event, data string) []byte {
	var buf bytes.Buffer
	if event != "" {
		buf.WriteString("event: ")
		buf.WriteString(event)
		buf.WriteByte('\n')
	}
	if data == "[DONE]" {
		buf.WriteString("data: [DONE]\n\n")
		return buf.Bytes()
	}
	for _, line := range strings.Split(data, "\n") {
		buf.WriteString("data: ")
		buf.WriteString(line)
		buf.WriteByte('\n')
	}
	buf.WriteByte('\n')
	return buf.Bytes()
}

// BuildErrorJSON is kept for callers that need the raw envelope.
func BuildErrorJSON(clientFormat, msg string) string {
	return BuildErrorBody(clientFormat, msg, "api_error", 503)
}

// ErrorMapOpenAI renders the OpenAI error envelope as a map (for gin.JSON).
func ErrorMapOpenAI(msg, errType string, code int) map[string]any {
	return map[string]any{
		"error": map[string]any{"message": msg, "type": errType, "code": code},
	}
}

// BuildGatewayMetaJSON constructs a _gateway_meta payload.
func BuildGatewayMetaJSON(chType, channel, apiKeyName, channelModel string) string {
	return mustJSONString(map[string]any{
		"_gateway_meta": true, "channel_type": chType, "channel": channel,
		"api_key_name": apiKeyName, "channel_model": channelModel,
	})
}

// BuildRoutingProgressJSON constructs a _routing_progress payload.
func BuildRoutingProgressJSON(phase, chType, channel, apiKeyName, channelModel string, retryIndex int, message string) string {
	node := map[string]any{
		"_routing_progress": true, "phase": phase, "channel_type": chType,
		"channel": channel, "api_key_name": apiKeyName, "channel_model": channelModel,
		"retry_index": retryIndex,
	}
	if message != "" {
		node["message"] = message
	}
	return mustJSONString(node)
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

var _ = context.Background
var _ = fmt.Sprintf
