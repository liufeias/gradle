/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.internal.core;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.internal.Actions;
import org.gradle.internal.BiAction;
import org.gradle.internal.Factories;
import org.gradle.internal.Factory;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.core.rule.describe.NestedModelRuleDescriptor;
import org.gradle.model.internal.core.rule.describe.SimpleModelRuleDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ThreadSafe
abstract public class ModelRegistrations {

    public static <T> Builder serviceInstance(ModelReference<T> modelReference, T instance) {
        return bridgedInstance(modelReference, instance)
            .service(true);
    }

    public static <T> Builder bridgedInstance(ModelReference<T> modelReference, T instance) {
        return unmanagedInstance(modelReference, Factories.constant(instance), Actions.doNothing());
    }

    public static <T> Builder unmanagedInstance(ModelReference<T> modelReference, final Factory<? extends T> factory) {
        return unmanagedInstance(modelReference, factory, Actions.doNothing());
    }

    public static <T> Builder unmanagedInstance(final ModelReference<T> modelReference, final Factory<? extends T> factory, final Action<? super MutableModelNode> initializer) {
        return unmanagedInstanceOf(modelReference, new Transformer<T, MutableModelNode>() {
            @Override
            public T transform(MutableModelNode modelNode) {
                T t = factory.create();
                initializer.execute(modelNode);
                return t;
            }
        });
    }

    public static <T> Builder unmanagedInstanceOf(final ModelReference<T> modelReference, final Transformer<? extends T, ? super MutableModelNode> factory) {
        return of(modelReference.getPath(), new Action<MutableModelNode>() {
            @Override
            public void execute(MutableModelNode modelNode) {
                T t = factory.transform(modelNode);
                modelNode.setPrivateData(modelReference.getType(), t);
            }
        })
            .withProjection(UnmanagedModelProjection.of(modelReference.getType()));
    }

    public static Builder of(ModelPath path) {
        return new Builder(path);
    }

    public static Builder of(ModelPath path, NodeInitializer initializer) {
        return new Builder(path, initializer);
    }

    public static Builder of(ModelPath path, ModelReference<?> input, BiAction<? super MutableModelNode, ? super List<ModelView<?>>> initializer) {
        return of(path, Collections.singletonList(input), initializer);
    }

    public static Builder of(ModelPath path, List<? extends ModelReference<?>> inputs, BiAction<? super MutableModelNode, ? super List<ModelView<?>>> initializer) {
        return new Builder(path)
            .action(ModelActionRole.Create, inputs, initializer);
    }

    public static Builder of(ModelPath path, Action<? super MutableModelNode> initializer) {
        return new Builder(path)
            .action(ModelActionRole.Create, initializer);
    }

    public static <T> Builder of(final ModelReference<T> modelReference, final Factory<? extends T> factory) {
        return of(modelReference.getPath(), new Action<MutableModelNode>() {
            @Override
            public void execute(MutableModelNode modelNode) {
                T value = factory.create();
                modelNode.setPrivateData(modelReference.getType(), value);
            }
        });
    }

    @NotThreadSafe
    public static class Builder {
        private final ModelReference<?> reference;
        private final List<ModelProjection> projections = new ArrayList<ModelProjection>();
        private final ListMultimap<ModelActionRole, ModelAction> actions = ArrayListMultimap.create();
        private final NodeInitializer nodeInitializer;
        private final DescriptorReference descriptorReference = new DescriptorReference();
        private boolean service;
        private boolean hidden;

        private Builder(ModelPath path) {
            this(path, null);
        }

        private Builder(ModelPath path, NodeInitializer nodeInitializer) {
            this.reference = ModelReference.of(path);
            this.nodeInitializer = nodeInitializer;
        }

        public Builder descriptor(String descriptor) {
            return descriptor(new SimpleModelRuleDescriptor(descriptor));
        }

        public Builder descriptor(ModelRuleDescriptor descriptor) {
            this.descriptorReference.descriptor = descriptor;
            return this;
        }

        public Builder descriptor(ModelRuleDescriptor parent, ModelRuleDescriptor child) {
            return descriptor(new NestedModelRuleDescriptor(parent, child));
        }

        public Builder descriptor(ModelRuleDescriptor parent, String child) {
            return descriptor(new NestedModelRuleDescriptor(parent, new SimpleModelRuleDescriptor(child)));
        }

        public Builder action(ModelActionRole role, ModelAction action) {
            this.actions.put(role, action);
            return this;
        }

        public Builder action(ModelActionRole role, Action<? super MutableModelNode> action) {
            return action(role, new NoInputsBuilderAction(reference, descriptorReference, action));
        }

        public Builder action(ModelActionRole role, Iterable<? extends ModelReference<?>> inputs, BiAction<? super MutableModelNode, ? super List<ModelView<?>>> action) {
            return action(role, new InputsUsingBuilderAction(reference, descriptorReference, inputs, action));
        }

        public Builder withProjection(ModelProjection projection) {
            projections.add(projection);
            return this;
        }

        public Builder hidden(boolean flag) {
            this.hidden = flag;
            return this;
        }

        public Builder service(boolean flag) {
            this.service = flag;
            return this;
        }

        public ModelRegistration build() {
            ModelRuleDescriptor descriptor = descriptorReference.descriptor;
            if (nodeInitializer != null) {
                actions.putAll(nodeInitializer.getActions(reference, descriptor));
            }
            if (!projections.isEmpty()) {
                action(ModelActionRole.Discover, AddProjectionsAction.of(reference, descriptor, projections));
            }
            return new DefaultModelRegistration(reference.getPath(), descriptor, service, hidden || service, actions);
        }

        private static class DescriptorReference {
            private ModelRuleDescriptor descriptor;
        }

        private static abstract class AbstractBuilderAction implements ModelAction {
            private final ModelReference<?> subject;
            private final DescriptorReference descriptorReference;

            public AbstractBuilderAction(ModelReference<?> subject, DescriptorReference descriptorReference) {
                this.subject = subject;
                this.descriptorReference = descriptorReference;
            }

            @Override
            public ModelReference<?> getSubject() {
                return subject;
            }

            @Override
            public ModelRuleDescriptor getDescriptor() {
                return descriptorReference.descriptor;
            }
        }

        private static class NoInputsBuilderAction extends AbstractBuilderAction {
            private final Action<? super MutableModelNode> action;

            public NoInputsBuilderAction(ModelReference<?> subject, DescriptorReference descriptorReference, Action<? super MutableModelNode> action) {
                super(subject, descriptorReference);
                this.action = action;
            }

            @Override
            public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
                action.execute(modelNode);
            }

            @Override
            public List<? extends ModelReference<?>> getInputs() {
                return Collections.emptyList();
            }
        }

        private static class InputsUsingBuilderAction extends AbstractBuilderAction {
            private final List<ModelReference<?>> inputs;
            private final BiAction<? super MutableModelNode, ? super List<ModelView<?>>> action;

            public InputsUsingBuilderAction(ModelReference<?> subject, DescriptorReference descriptorReference, Iterable<? extends ModelReference<?>> inputs, BiAction<? super MutableModelNode, ? super List<ModelView<?>>> action) {
                super(subject, descriptorReference);
                this.inputs = ImmutableList.copyOf(inputs);
                this.action = action;
            }

            @Override
            public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
                action.execute(modelNode, inputs);
            }

            @Override
            public List<? extends ModelReference<?>> getInputs() {
                return inputs;
            }
        }
    }
}
