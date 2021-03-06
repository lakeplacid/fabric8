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
package io.fabric8.jolokia;

import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.Configurer;
import io.fabric8.api.scr.ValidatingReference;
import org.apache.felix.scr.annotations.*;
import org.jolokia.config.ConfigKey;
import org.jolokia.config.Configuration;
import org.jolokia.osgi.servlet.JolokiaServlet;
import org.jolokia.restrictor.Restrictor;
import org.osgi.framework.BundleContext;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.util.*;

@Component(name = "io.fabric8.jolokia",
        label = "Fabric8 Jolokia",
        description = "Configuration for the Fabric8 Jolokia Service which exposes JMX securely over HTTP/JSON",
        policy = ConfigurationPolicy.OPTIONAL, metatype = true, immediate = true)
public class FabricJolokia extends AbstractComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricJolokia.class);

    @Property(name = "alias", label = "Servlet Alias", description = "Servlet Alias", value = "/jolokia")
    private String alias;

    @Property(name = "realm", label = "Jaas Realm", description = "Jaas Realm", value = "karaf")
    private String realm;

    @Property(name = "role", label = "Jaas Role", description = "Jaas Role", value = "admin,manager,viewer,Monitor,Operator,Maintainer,Deployer,Auditor,Administrator,SuperUser")
    private String[] role;

    private HttpContext context;


    @Reference
    private Configurer configurer;
    @Reference(referenceInterface = HttpService.class)
    private final ValidatingReference<HttpService> httpService = new ValidatingReference<HttpService>();

    @Activate
    void activate(BundleContext bundleContext, Map<String, String> properties) throws Exception {
        configurer.configure(properties, this);
        context = new JolokiaSecureHttpContext(realm, role);
        Hashtable<String,String> initProps = new Hashtable<>();
        injectSystemProperties(initProps);
        httpService.get().registerServlet(getServletAlias(), new JolokiaServlet(bundleContext){
            @Override
            protected Restrictor createRestrictor(Configuration config) {
                String policyLocation = config.get(ConfigKey.POLICY_LOCATION);
                return new RBACRestrictor(policyLocation);
            }
        }, initProps, context);
        activateComponent();
    }

    protected void injectSystemProperties(Hashtable<String,String> initProps) {
        java.util.Properties properties = System.getProperties();
        for(String prop : properties.stringPropertyNames()){
            if(prop.startsWith("jolokia.")){
                initProps.put(prop.substring(prop.indexOf(".") + 1), properties.getProperty(prop));
            }
        }
    }

    @Deactivate
    void deactivate() {
        try {
            httpService.get().unregister(getServletAlias());
        } catch (Throwable t) {
            LOGGER.warn("Error while unregistering jolokia.");
        }
    }


    public String getServletAlias() {
        return alias;
    }


    void bindHttpService(HttpService service) {
        this.httpService.bind(service);
    }

    void unbindHttpService(HttpService service) {
        this.httpService.unbind(service);
    }

}
