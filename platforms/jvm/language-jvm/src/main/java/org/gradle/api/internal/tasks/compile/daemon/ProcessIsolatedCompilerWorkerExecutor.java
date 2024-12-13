/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.api.internal.tasks.compile.daemon;

import org.gradle.initialization.layout.ProjectCacheDir;
import org.gradle.workers.internal.ActionExecutionSpecFactory;
import org.gradle.workers.internal.DaemonForkOptions;
import org.gradle.workers.internal.ForkedWorkerRequirement;
import org.gradle.workers.internal.IsolatedClassLoaderWorkerRequirement;
import org.gradle.workers.internal.WorkerDaemonFactory;

import java.io.File;

public class ProcessIsolatedCompilerWorkerExecutor extends AbstractIsolatedCompilerWorkerExecutor {

    private final File settingsDirectory;
    private final ProjectCacheDir projectCacheDir;

    public ProcessIsolatedCompilerWorkerExecutor(WorkerDaemonFactory delegate, ActionExecutionSpecFactory actionExecutionSpecFactory, File settingsDirectory, ProjectCacheDir projectCacheDir) {
        super(delegate, actionExecutionSpecFactory);
        this.settingsDirectory = settingsDirectory;
        this.projectCacheDir = projectCacheDir;
    }

    @Override
    public IsolatedClassLoaderWorkerRequirement getIsolatedWorkerRequirement(DaemonForkOptions daemonForkOptions) {
        // Compiler daemons do not rely on project cache directory
        return new ForkedWorkerRequirement(settingsDirectory, daemonForkOptions.getWorkingDir(), projectCacheDir.getDir(), daemonForkOptions);
    }
}
