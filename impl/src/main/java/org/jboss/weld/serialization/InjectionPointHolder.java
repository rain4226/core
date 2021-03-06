/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.serialization;

import java.io.Serializable;

import javax.enterprise.inject.spi.AnnotatedCallable;
import javax.enterprise.inject.spi.AnnotatedConstructor;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionPoint;

import org.jboss.weld.exceptions.IllegalArgumentException;
import org.jboss.weld.exceptions.IllegalStateException;
import org.jboss.weld.logging.messages.BeanMessage;
import org.jboss.weld.util.reflection.Reflections;

/**
 * Serializable holder for {@link InjectionPoint}.
 *
 * @author Jozef Hartinger
 *
 */
public class InjectionPointHolder extends AbstractSerializableHolder<InjectionPoint> {

    private static final long serialVersionUID = -6128821485743815308L;

    private final InjectionPointIdentifier identifier;

    public InjectionPointHolder(String contextId, InjectionPoint ip) {
        super(ip);
        if (ip.getBean() == null) {
            this.identifier = new NoopInjectionPointIdentifier(ip);
        } else if (ip.getAnnotated() instanceof AnnotatedField<?>) {
            AnnotatedField<?> field = Reflections.cast(ip.getAnnotated());
            this.identifier = new FieldInjectionPointIdentifier(contextId, ip.getBean(), field);
        } else if (ip.getAnnotated() instanceof AnnotatedParameter<?>) {
            AnnotatedParameter<?> parameter = Reflections.cast(ip.getAnnotated());
            if (parameter.getDeclaringCallable() instanceof AnnotatedConstructor<?>) {
                AnnotatedConstructor<?> constructor = Reflections.cast(parameter.getDeclaringCallable());
                this.identifier = new ConstructorParameterInjectionPointIdentifier(contextId, ip.getBean(), parameter.getPosition(), constructor);
            } else if (parameter.getDeclaringCallable() instanceof AnnotatedMethod<?>) {
                AnnotatedMethod<?> method = Reflections.cast(parameter.getDeclaringCallable());
                this.identifier = new MethodParameterInjectionPointIdentifier(contextId, ip.getBean(), parameter.getPosition(), method);
            } else {
                throw new IllegalArgumentException(BeanMessage.INVALID_ANNOTATED_CALLABLE, parameter.getDeclaringCallable());
            }
        } else {
            throw new IllegalArgumentException(BeanMessage.INVALID_ANNOTATED_OF_INJECTION_POINT, ip.getAnnotated(), ip);
        }
    }

    @Override
    protected InjectionPoint initialize() {
        return identifier.restoreInjectionPoint();
    }

    private interface InjectionPointIdentifier extends Serializable {
        InjectionPoint restoreInjectionPoint();
    }

    /**
     * Noop implementation of {@link InjectionPointIdentifier}. An instance is serializable as long as the underlying
     * {@link InjectionPoint} is serializable. This identifier should only be used to wrap {@link InjectionPoint}s that do not
     * belong to a bean.
     *
     * @author Jozef Hartinger
     *
     */
    private static class NoopInjectionPointIdentifier implements InjectionPointIdentifier {

        private static final long serialVersionUID = 6952579330771485841L;

        private final transient InjectionPoint ip;

        public NoopInjectionPointIdentifier(InjectionPoint ip) {
            this.ip = ip;
        }

        @Override
        public InjectionPoint restoreInjectionPoint() {
            return ip;
        }

    }

    private abstract static class AbstractInjectionPointIdentifier implements InjectionPointIdentifier {

        private static final long serialVersionUID = -8167922066673252787L;

        private final BeanHolder<?> bean;

        public AbstractInjectionPointIdentifier(String contextId, Bean<?> bean) {
            this.bean = BeanHolder.of(contextId, bean);
        }

        @Override
        public InjectionPoint restoreInjectionPoint() {
            InjectionPoint injectionPoint = null;
            for (InjectionPoint ip : bean.get().getInjectionPoints()) {
                if (matches(ip)) {
                    if (injectionPoint != null) {
                        throw new IllegalStateException(BeanMessage.UNABLE_TO_RESTORE_INJECTION_POINT_MULTIPLE, bean.get(), injectionPoint, ip);
                    }
                    injectionPoint = ip;
                }
            }
            if (injectionPoint == null) {
                throw new IllegalStateException(BeanMessage.UNABLE_TO_RESTORE_INJECTION_POINT, bean.get());
            }
            return injectionPoint;
        }

        protected abstract boolean matches(InjectionPoint ip);
    }

    private static class FieldInjectionPointIdentifier extends AbstractInjectionPointIdentifier {

        private static final long serialVersionUID = 4581216810217284043L;

        private final FieldHolder field;

        public FieldInjectionPointIdentifier(String contextId, Bean<?> bean, AnnotatedField<?> field) {
            super(contextId, bean);
            this.field = new FieldHolder(field.getJavaMember());
        }

        @Override
        protected boolean matches(InjectionPoint ip) {
            if (ip.getAnnotated() instanceof AnnotatedField<?>) {
                AnnotatedField<?> annotatedField = Reflections.cast(ip.getAnnotated());
                return (field.get().equals(annotatedField.getJavaMember()));
            }
            return false;
        }
    }

    private abstract static class AbstractParameterInjectionPointIdentifier extends AbstractInjectionPointIdentifier {

        private static final long serialVersionUID = -3618042716814281161L;

        private final int position;

        public AbstractParameterInjectionPointIdentifier(String contextId, Bean<?> bean, int position) {
            super(contextId, bean);
            this.position = position;
        }

        @Override
        protected boolean matches(InjectionPoint ip) {
            if (ip.getAnnotated() instanceof AnnotatedParameter<?>) {
                AnnotatedParameter<?> annotatedParameter = Reflections.cast(ip.getAnnotated());
                return position == annotatedParameter.getPosition() && matches(ip, annotatedParameter.getDeclaringCallable());
            }
            return false;
        }

        protected abstract boolean matches(InjectionPoint ip, AnnotatedCallable<?> annotatedCallable);
    }

    private static class ConstructorParameterInjectionPointIdentifier extends AbstractParameterInjectionPointIdentifier {

        private static final long serialVersionUID = 638702977751948835L;

        private final ConstructorHolder<?> constructor;

        public ConstructorParameterInjectionPointIdentifier(String contextId, Bean<?> bean, int position, AnnotatedConstructor<?> constructor) {
            super(contextId, bean, position);
            this.constructor = ConstructorHolder.of(constructor.getJavaMember());
        }

        @Override
        protected boolean matches(InjectionPoint ip, AnnotatedCallable<?> annotatedCallable) {
            if (annotatedCallable instanceof AnnotatedConstructor<?>) {
                AnnotatedConstructor<?> annotatedConstructor = Reflections.cast(annotatedCallable);
                return constructor.get().equals(annotatedConstructor.getJavaMember());
            }
            return false;
        }
    }

    private static class MethodParameterInjectionPointIdentifier extends AbstractParameterInjectionPointIdentifier {

        private static final long serialVersionUID = -3263543692438746424L;

        private final MethodHolder method;

        public MethodParameterInjectionPointIdentifier(String contextId, Bean<?> bean, int position, AnnotatedMethod<?> constructor) {
            super(contextId, bean, position);
            this.method = MethodHolder.of(constructor);
        }

        @Override
        protected boolean matches(InjectionPoint ip, AnnotatedCallable<?> annotatedCallable) {
            if (annotatedCallable instanceof AnnotatedMethod<?>) {
                AnnotatedMethod<?> annotatedMethod = Reflections.cast(annotatedCallable);
                return method.get().equals(annotatedMethod.getJavaMember());
            }
            return false;
        }
    }
}
