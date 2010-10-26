/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.openejb.cdi;

import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.webbeans.config.OpenWebBeansConfiguration;
import org.apache.webbeans.corespi.ServiceLoader;
import org.apache.webbeans.exception.WebBeansException;
import org.apache.webbeans.spi.ContainerLifecycle;
import org.apache.webbeans.spi.ResourceInjectionService;

/**
 * @version $Rev:$ $Date:$
 */
public class ThreadSingletonServiceImpl implements ThreadSingletonService {

    public static final Logger logger = Logger.getInstance(LogCategory.OPENEJB_STARTUP, ThreadSingletonServiceImpl.class);
    //this needs to be static because OWB won't tell us what the existing SingletonService is and you can't set it twice.
    private static final ThreadLocal<OWBContext> contexts = new ThreadLocal<OWBContext>();
    private final ClassLoader classLoader;

    public ThreadSingletonServiceImpl(ClassLoader classLoader) {
        this.classLoader = classLoader;

    }

    @Override
    public void initialize(StartupObject startupObject) {
        //initialize owb context, cf geronimo's OpenWebBeansGBean
        OWBContext owbContext = new OWBContext();
        startupObject.getAppContext().set(OWBContext.class, owbContext);
        Object old = contextEntered(owbContext);
        try {
            setConfiguration(OpenWebBeansConfiguration.getInstance());
            try {
                ServiceLoader.getService(ContainerLifecycle.class).startApplication(startupObject);
            } catch (Exception e) {
                throw new RuntimeException("couldn't start owb context", e);
            }
        } finally {
            contextExited(old);
        }
    }

    //not sure what openejb will need

    private void setConfiguration(OpenWebBeansConfiguration configuration) {
        //from CDI builder
        configuration.setProperty(OpenWebBeansConfiguration.INTERCEPTOR_FORCE_NO_CHECKED_EXCEPTIONS, "false");
//        configuration.setProperty(OpenWebBeansConfiguration.APPLICATION_IS_JSP, "true");

        configuration.setProperty(OpenWebBeansConfiguration.CONTAINER_LIFECYCLE, OpenEJBLifecycle.class.getName());
//        configuration.setProperty(OpenWebBeansConfiguration.JNDI_SERVICE, NoopJndiService.class.getName());
        configuration.setProperty(OpenWebBeansConfiguration.SCANNER_SERVICE, CdiScanner.class.getName());
        configuration.setProperty(OpenWebBeansConfiguration.CONTEXTS_SERVICE, CdiAppContextsService.class.getName());
        configuration.setProperty(ResourceInjectionService.class.getName(), CdiResourceInjectionService.class.getName());
    }

    @Override
    public Object contextEntered(OWBContext newOWBContext) {
        OWBContext oldContext = contexts.get();
        contexts.set(newOWBContext);
        contextMessage(newOWBContext, "Enter:");
        return oldContext;
    }

    private void contextMessage(OWBContext newOWBContext, String prefix) {
        if (logger.isDebugEnabled()) {
            StringBuilder b = new StringBuilder(" owbContext: " + newOWBContext + "\n");
            for (Object service : newOWBContext.getSingletons().values()) {
                b.append("  " + service + "\n");
            }
            logger.debug(b.toString());
        }
    }

    @Override
    public void contextExited(Object oldOWBContext) {
        if (oldOWBContext != null && !(oldOWBContext instanceof OWBContext)) throw new IllegalArgumentException("ThreadSingletonServiceImpl can only be used with OWBContext, not " + oldOWBContext.getClass().getName());
        contexts.set((OWBContext) oldOWBContext);
    }

    @Override
    public Object get(Object key, String singletonClassName) {
        OWBContext context = getContext();
        contextMessage(context, "ThreadSingletonService looking for " + singletonClassName);
        Object service = context.getSingletons().get(singletonClassName);
        if (service == null) {
            try {
                Class clazz = classLoader.loadClass(singletonClassName);
                service = clazz.newInstance();
            } catch (ClassNotFoundException e) {
                throw new WebBeansException("Could not locate requested class " + singletonClassName + " in classloader " + classLoader, e);
            } catch (InstantiationException e) {
                throw new WebBeansException("Could not create instance of class " + singletonClassName, e);
            } catch (IllegalAccessException e) {
                throw new WebBeansException("Could not create instance of class " + singletonClassName, e);
            } catch (NoClassDefFoundError e) {
                throw new WebBeansException("Could not locate requested class " + singletonClassName + " in classloader " + classLoader, e);
            }
            context.getSingletons().put(singletonClassName, service);
        }
        return service;
    }

    private OWBContext getContext() {
        OWBContext context = contexts.get();
        if (context == null) {
            throw new IllegalStateException("On a thread without an initialized context");
        }
        return context;
    }

    @Override
    public void clear(Object key) {
        contextMessage(getContext(), "clearing ");
        getContext().getSingletons().clear();
    }

    @Override
    public boolean isExist(Object key, String singletonClassName) {
        return getContext().getSingletons().containsKey(singletonClassName);
    }

    @Override
    public Object getExist(Object key, String singletonClassName) {
        return getContext().getSingletons().get(singletonClassName);
    }

    @Override
    public Object getKey(Object singleton) {
        return null;
    }
}
