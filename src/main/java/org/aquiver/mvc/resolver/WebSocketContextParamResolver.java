/*
 * MIT License
 *
 * Copyright (c) 2019 1619kHz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.aquiver.mvc.resolver;

import org.aquiver.RequestContext;
import org.aquiver.mvc.route.RouteParam;
import org.aquiver.mvc.route.RouteParamType;
import org.aquiver.websocket.WebSocketContext;

import java.lang.reflect.Parameter;

/**
 * @author WangYi
 * @since 2020/7/13
 */
public class WebSocketContextParamResolver implements ParamResolver{
  @Override
  public boolean support(Parameter parameter) {
    return parameter.getType().isAssignableFrom(WebSocketContext.class);
  }

  @Override
  public RouteParam resolve(Parameter parameter, String paramName) {
    RouteParam handlerParam = new RouteParam();
    handlerParam.setDataType(parameter.getType());
    handlerParam.setName("");
    handlerParam.setRequired(true);
    handlerParam.setType(RouteParamType.WEBSOCKET_CONTEXT);
    return handlerParam;
  }

  @Override
  public Object dispen(Class<?> paramType, String paramName, RequestContext requestContext) {
    return requestContext.webSocketContext();
  }

  @Override
  public RouteParamType dispenType() {
    return RouteParamType.WEBSOCKET_CONTEXT;
  }
}
