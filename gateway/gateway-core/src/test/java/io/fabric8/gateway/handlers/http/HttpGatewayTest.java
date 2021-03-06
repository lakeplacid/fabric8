/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.gateway.handlers.http;


import io.fabric8.gateway.CallDetailRecord;
import io.fabric8.gateway.ServiceDTO;
import io.fabric8.gateway.handlers.detecting.FutureHandler;
import io.fabric8.gateway.loadbalancer.LoadBalancer;
import io.fabric8.gateway.loadbalancer.RoundRobinLoadBalancer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.MultiMap;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.vertx.java.core.http.HttpHeaders.CONTENT_LENGTH;
import static org.vertx.java.core.http.HttpHeaders.TRANSFER_ENCODING;

/**
 */
public class HttpGatewayTest extends AbstractHttpGatewayTest {

    private static final transient Logger LOG = LoggerFactory.getLogger(HttpGatewayTest.class);

    @Override
    public HttpServer startRestEndpoint() throws InterruptedException {
        restEndpointServer = vertx.createHttpServer();
        restEndpointServer.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {
                request.response().putHeader("content-type", "text/plain");
                request.response().end("Hello: "+request.query());
            }
        });

        FutureHandler<AsyncResult<HttpServer>> future = new FutureHandler<>();
        restEndpointServer.listen(8181, "0.0.0.0", future);
        future.await();
        return restEndpointServer;
    }

    @Override
    public HttpGatewayServer startHttpGateway() {

        if( restEndpointServer!=null ) {
            LoadBalancer loadBalancer=new RoundRobinLoadBalancer();

            ServiceDTO serviceDetails = new ServiceDTO();
            serviceDetails.setContainer("local");
            serviceDetails.setVersion("1");

            mappedServices.put("/hello/world", new MappedServices("http://localhost:8181", serviceDetails, loadBalancer, false));
        }

        HttpGatewayHandler handler = new HttpGatewayHandler(vertx, new HttpGateway(){
            @Override
            public void addMappingRuleConfiguration(HttpMappingRule mappingRule) {
            }

            @Override
            public void removeMappingRuleConfiguration(HttpMappingRule mappingRule) {
            }

            @Override
            public Map<String, MappedServices> getMappedServices() {
                return mappedServices;
            }

            @Override
            public boolean isEnableIndex() {
                return true;
            }

            @Override
            public InetSocketAddress getLocalAddress() {
                return new InetSocketAddress("0.0.0.0", 8080);
            }

            @Override
            public void addCallDetailRecord(CallDetailRecord cdr) {
            }
        });
        httpGatewayServer = new HttpGatewayServer(vertx, handler, null, 8080);
        httpGatewayServer.setHost("localhost");
        httpGatewayServer.init();
        return httpGatewayServer;
    }

    /**
     * Validates that query params are passed to the proxied service.
     * Used to verify that ENTESB-5437 is fixed.
     *
     * @throws Exception
     */
    @Test
    public void testENTESB5437() throws Exception {

        startRestEndpoint();
        startHttpGateway();

        LOG.info("Requesting...");

        final FutureHandler<String> future = new FutureHandler<>();
        vertx.createHttpClient().setHost("localhost").setPort(8080).get("/hello/world?wsdl", new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse event) {
                event.bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer event) {
                        future.handle(event.toString());
                    }
                });
            }
        }).end();
        assertEquals( "Hello: wsdl", future.await());

        stopHttpGateway();
        stopVertx();
    }

    @Test
    public void testENTESB7600() throws Exception {
        //response can not contain CONTENT_LENGTH and TRANSFER_ENCODING see https://tools.ietf.org/html/rfc7230#section-3.3.3
        startRestEndpoint();
        startHttpGateway();

        System.out.println("Requesting...");

        final FutureHandler<HttpClientResponse> future = new FutureHandler<>();
        vertx.createHttpClient().setHost("localhost").setPort(8080).get("/hello/world?wsdl", new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse event) {
                future.handle(event);
            }
        }).end();

        MultiMap responseHeaders = future.await().headers();

        assertTrue( ( responseHeaders.contains( CONTENT_LENGTH ) &&  !responseHeaders.contains( TRANSFER_ENCODING ) )
                    || ( !responseHeaders.contains( CONTENT_LENGTH ) &&  responseHeaders.contains( TRANSFER_ENCODING )) );

        stopHttpGateway();
        stopVertx();
    }

}
