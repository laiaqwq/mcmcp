package dev.mcmcp.transport.netty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.ToolDispatcher;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.observability.Metrics;
import dev.mcmcp.protocol.JsonRpcErrors;
import dev.mcmcp.protocol.McpRequestValidator;
import dev.mcmcp.protocol.McpResponses;
import dev.mcmcp.protocol.StrictJsonReader;
import dev.mcmcp.util.CancellationToken;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Handles a fully aggregated HTTP request: parses JSON-RPC, validates MCP headers,
 * dispatches to the ToolDispatcher, and writes the JSON response.
 * See IMPLEMENTATION.md §8, §9.3.
 */
public final class ProtocolDispatchHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ToolDispatcher dispatcher;
    private final Metrics metrics;
    private final long requestTimeoutNanos;

    public ProtocolDispatchHandler(ToolDispatcher dispatcher, Metrics metrics, long requestTimeoutNanos) {
        super(false); // do not auto-release, we release manually
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.requestTimeoutNanos = requestTimeoutNanos;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, null);
            return;
        }

        metrics.requestStarted();
        long startNanos = System.nanoTime();

        // Read MCP headers
        String protocolVersion = req.headers().get("MCP-Protocol-Version");
        String mcpMethod = req.headers().get("Mcp-Method");
        String mcpName = req.headers().get("Mcp-Name");

        // Validate protocol version header
        if (!McpRequestValidator.isValidProtocolVersion(protocolVersion)) {
            JsonObject data = new JsonObject();
            var versions = new com.google.gson.JsonArray();
            versions.add(McpRequestValidator.PROTOCOL_VERSION);
            data.add("supportedVersions", versions);
            sendJsonRpcError(ctx, null, JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION,
                "unsupported protocol version", data, HttpResponseStatus.BAD_REQUEST);
            metrics.requestFinished();
            return;
        }

        // Parse JSON body strictly
        ByteBuf content = req.content();
        if (content.readableBytes() == 0) {
            sendJsonRpcError(ctx, null, JsonRpcErrors.PARSE_ERROR, "empty body",
                null, HttpResponseStatus.BAD_REQUEST);
            metrics.requestFinished();
            return;
        }

        JsonObject body;
        try {
            String jsonStr = content.toString(StandardCharsets.UTF_8);
            body = StrictJsonReader.readObject(jsonStr);
        } catch (Exception e) {
            sendJsonRpcError(ctx, null, JsonRpcErrors.PARSE_ERROR, "parse error: " + e.getMessage(),
                null, HttpResponseStatus.BAD_REQUEST);
            metrics.requestFinished();
            return;
        } finally {
            req.release();
        }

        // Validate JSON-RPC envelope
        var envelopeResult = McpRequestValidator.validateEnvelope(body);
        if (!envelopeResult.valid()) {
            sendJsonRpcError(ctx, null, envelopeResult.errorCode(), envelopeResult.errorMessage(),
                envelopeResult.errorData(), HttpResponseStatus.BAD_REQUEST);
            metrics.requestFinished();
            return;
        }

        JsonElement id = envelopeResult.id();
        String method = envelopeResult.method();
        JsonObject params = envelopeResult.params();

        // Validate Mcp-Method header matches body method
        if (!McpRequestValidator.methodMatchesHeader(mcpMethod, method)) {
            sendJsonRpcError(ctx, id, JsonRpcErrors.HEADER_MISMATCH,
                "Mcp-Method header does not match body method", null, HttpResponseStatus.BAD_REQUEST);
            metrics.requestFinished();
            return;
        }

        // Validate params._meta
        var metaResult = McpRequestValidator.validateMeta(params);
        if (!metaResult.valid()) {
            sendJsonRpcError(ctx, id, metaResult.errorCode(), metaResult.errorMessage(),
                metaResult.errorData(), HttpResponseStatus.BAD_REQUEST);
            metrics.requestFinished();
            return;
        }

        // For tools/call: validate Mcp-Name header
        if (McpRequestValidator.METHOD_TOOLS_CALL.equals(method)) {
            var nameResult = McpRequestValidator.validateToolName(mcpName, params);
            if (!nameResult.valid()) {
                sendJsonRpcError(ctx, id, nameResult.errorCode(), nameResult.errorMessage(),
                    null, HttpResponseStatus.BAD_REQUEST);
                metrics.requestFinished();
                return;
            }
        }

        // Dispatch
        long deadlineNanos = startNanos + requestTimeoutNanos;
        CancellationToken cancellation = new CancellationToken();

        // Cancel on channel close
        ctx.channel().closeFuture().addListener(f -> cancellation.cancel());

        dispatcher.dispatch(id, method, params, deadlineNanos)
            .whenComplete((response, throwable) -> {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                metrics.requestFinished();

                if (throwable != null) {
                    McmcpLogger.error("dispatch_exception", "method", method,
                        "duration_ms", String.valueOf(durationMs), "error", throwable.getMessage());
                    sendJsonRpcError(ctx, id, JsonRpcErrors.INTERNAL_ERROR, "internal error",
                        null, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    return;
                }

                if (cancellation.isCancelled()) {
                    metrics.lateCompletion();
                    McmcpLogger.debug("late_completion", "method", method, "duration_ms", String.valueOf(durationMs));
                    return;
                }

                sendJsonResponse(ctx, response);
                McmcpLogger.debug("request_complete", "method", method,
                    "duration_ms", String.valueOf(durationMs));
            });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        McmcpLogger.error("handler_exception", "error", cause.getMessage());
        sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, null);
        ctx.close();
    }

    private void sendJsonResponse(ChannelHandlerContext ctx, JsonObject response) {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        httpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendJsonRpcError(ChannelHandlerContext ctx, JsonElement id, int code, String message,
                                   JsonObject data, HttpResponseStatus status) {
        JsonObject response = (id != null)
            ? McpResponses.error(id, code, message, data)
            : McpResponses.errorNoId(code, message);
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        httpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
