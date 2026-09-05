package dev.mcmcp.transport.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.ReferenceCountUtil;

/**
 * Validates HTTP headers before body aggregation.
 * Rejects invalid requests early to avoid buffering large bodies.
 */
final class HeaderGateHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private final HttpSecurityGate gate;

    HeaderGateHandler(HttpSecurityGate gate) {
        super(false);
        this.gate = gate;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            sendSimpleError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        var result = gate.check(req);
        if (!result.passed()) {
            sendSimpleError(ctx, result.status());
            return;
        }

        // Pass through (keep reference for aggregator)
        ctx.fireChannelRead(req);
    }

    private void sendSimpleError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(response).addListener(f -> ctx.close());
    }
}
