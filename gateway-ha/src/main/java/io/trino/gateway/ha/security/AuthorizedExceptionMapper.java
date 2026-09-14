/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.security;

import io.trino.gateway.ha.domain.Result;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.jersey.server.internal.LocalizationMessages;

@Provider
public class AuthorizedExceptionMapper
        implements ExceptionMapper<ForbiddenException>
{
    @Context
    private UriInfo uriInfo;

    @Override
    public Response toResponse(ForbiddenException exception)
    {
        if (exception.getMessage().equals(LocalizationMessages.USER_NOT_AUTHORIZED())) {
            if (uriInfo != null && (uriInfo.getPath().equals("gateway/transactions") || uriInfo.getPath().startsWith("gateway/transactions/"))) {
                return Response.status(Response.Status.FORBIDDEN).entity(Result.fail(Response.Status.FORBIDDEN)).build();
            }
            return Response.ok(Result.fail(Response.Status.UNAUTHORIZED)).build();
        }
        return exception.getResponse();
    }
}
