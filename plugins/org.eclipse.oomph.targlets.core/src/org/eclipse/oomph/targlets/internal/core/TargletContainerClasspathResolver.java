/*
 * Copyright (c) 2014 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.targlets.internal.core;

import org.eclipse.oomph.p2.core.Profile;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.variables.IDynamicVariable;
import org.eclipse.core.variables.IDynamicVariableResolver;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.IFileArtifactRepository;

import java.io.File;

/**
 * @author Eike Stepper
 */
public class TargletContainerClasspathResolver implements IDynamicVariableResolver
{
  public String resolveValue(IDynamicVariable variable, String containerID) throws CoreException
  {
    StringBuilder builder = new StringBuilder();

    TargletContainerDescriptor descriptor = TargletContainerDescriptorManager.getInstance().getDescriptor(containerID, new NullProgressMonitor());
    if (descriptor != null)
    {
      Profile profile = descriptor.getWorkingProfile();
      if (profile != null)
      {
        IFileArtifactRepository artifactRepository = profile.getBundlePool().getFileArtifactRepository();

        for (IInstallableUnit iu : profile.query(QueryUtil.createIUAnyQuery(), new NullProgressMonitor()))
        {
          for (IArtifactKey artifactKey : iu.getArtifacts())
          {
            if ("osgi.bundle".equals(artifactKey.getClassifier()) && !artifactKey.getId().endsWith(".source"))
            {
              File file = artifactRepository.getArtifactFile(artifactKey);
              if (file != null)
              {
                if (builder.length() != 0)
                {
                  builder.append(";");
                }

                builder.append(file.getAbsolutePath());
              }
            }
          }
        }
      }
    }

    return builder.toString();
  }
}