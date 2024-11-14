/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.netty.body;

import io.micronaut.buffer.netty.NettyByteBufferFactory;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Internal
public final class NettyByteBodyFactory extends ByteBodyFactory {
    private final EventLoop eventLoop;

    public NettyByteBodyFactory(@NonNull Channel channel) {
        super(new NettyByteBufferFactory(channel.alloc()));
        this.eventLoop = channel.eventLoop();
    }

    private ByteBufAllocator alloc() {
        return (ByteBufAllocator) byteBufferFactory().getNativeAllocator();
    }

    @Override
    public @NonNull CloseableAvailableByteBody adapt(@NonNull ByteBuffer<?> buffer) {
        if (buffer.asNativeBuffer() instanceof ByteBuf bb) {
            return new AvailableNettyByteBody(bb);
        }
        return super.adapt(buffer);
    }

    @Override
    public @NonNull CloseableAvailableByteBody adapt(byte @NonNull [] array) {
        return new AvailableNettyByteBody(Unpooled.wrappedBuffer(array));
    }

    @Override
    public @NonNull CloseableAvailableByteBody createEmpty() {
        return AvailableNettyByteBody.empty();
    }

    @Override
    public @NonNull CloseableAvailableByteBody copyOf(@NonNull CharSequence cs, @NonNull Charset charset) {
        ByteBuf byteBuf = charset == StandardCharsets.UTF_8 ?
            ByteBufUtil.writeUtf8(alloc(), cs) :
            ByteBufUtil.encodeString(alloc(), CharBuffer.wrap(cs), charset);
        return new AvailableNettyByteBody(byteBuf);
    }

    @Override
    public @NonNull CloseableAvailableByteBody copyOf(@NonNull InputStream stream) throws IOException {
        ByteBuf buffer = alloc().buffer();
        boolean free = true;
        try {
            while (true) {
                if (buffer.writeBytes(stream, 4096) == -1) {
                    break;
                }
            }
            free = false;
            return new AvailableNettyByteBody(buffer);
        } finally {
            if (free) {
                buffer.release();
            }
        }
    }
}
