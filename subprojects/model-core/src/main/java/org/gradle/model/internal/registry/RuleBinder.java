/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.model.internal.registry;

import net.jcip.annotations.NotThreadSafe;
import org.gradle.api.Action;
import org.gradle.api.Nullable;
import org.gradle.model.internal.core.ModelPath;
import org.gradle.model.internal.core.ModelReference;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The progressive binding of the subject/inputs of the references of a model rule.
 *
 * The binder is not responsible for finding out what the references should be bound to.
 * It merely manages the state of the rule binding.
 * The {@link #bindSubject} and {@link #bindInput} methods are called by the model registry/graph.
 *
 * This type is mutable.
 */
@NotThreadSafe
public class RuleBinder<T> {

    private final ModelReference<T> subjectReference;
    private final List<? extends ModelReference<?>> inputReferences;

    private final ModelRuleDescriptor descriptor;

    private final ModelPath scope;

    private Action<? super RuleBinder<T>> onBind;

    private int inputsBound;

    private ModelBinding<T> subjectBinding;
    private List<ModelBinding<?>> inputBindings;

    public RuleBinder(@Nullable ModelReference<T> subjectReference, List<? extends ModelReference<?>> inputReferences, ModelRuleDescriptor descriptor, ModelPath scope,
                      Action<? super RuleBinder<T>> onBind) {
        this.subjectReference = subjectReference;
        this.inputReferences = inputReferences;
        this.descriptor = descriptor;
        this.scope = scope;
        this.onBind = onBind;

        this.inputBindings = inputReferences.isEmpty() ? Collections.<ModelBinding<?>>emptyList() : Arrays.asList(new ModelBinding<?>[inputReferences.size()]); // fix size
    }

    @Nullable
    public ModelReference<T> getSubjectReference() {
        return subjectReference;
    }

    public List<? extends ModelReference<?>> getInputReferences() {
        return inputReferences;
    }

    public ModelBinding<T> getSubjectBinding() {
        return subjectBinding;
    }

    public List<ModelBinding<?>> getInputBindings() {
        return inputBindings;
    }

    public ModelRuleDescriptor getDescriptor() {
        return descriptor;
    }

    public ModelPath getScope() {
        return scope;
    }

    public void bindSubject(ModelNodeInternal modelNode) {
        assert this.subjectBinding == null;
        this.subjectBinding = bind(subjectReference, modelNode);
    }

    public void bindInput(int i, ModelNodeInternal modelNode) {
        assert this.inputBindings.get(i) == null;
        this.inputBindings.set(i, bind(inputReferences.get(i), modelNode));
        inputsBound += 1;
    }

    public boolean maybeFire() {
        if (isBound()) {
            fire();
            return true;
        } else {
            return false;
        }
    }

    public boolean isBound() {
        return (subjectReference == null || subjectBinding != null) && inputsBound == inputReferences.size();
    }

    public void fire() {
        onBind.execute(this);
        onBind = null; // let go for gc
    }

    private static <I> ModelBinding<I> bind(ModelReference<I> reference, ModelNodeInternal modelNode) {
        return ModelBinding.of(reference, modelNode);
    }
}
