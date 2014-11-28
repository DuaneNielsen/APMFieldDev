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
package org.eclipse.oomph.p2.internal.core;

import org.eclipse.oomph.p2.P2Exception;
import org.eclipse.oomph.p2.ProfileDefinition;
import org.eclipse.oomph.p2.Repository;
import org.eclipse.oomph.p2.Requirement;
import org.eclipse.oomph.p2.core.Agent;
import org.eclipse.oomph.p2.core.BundlePool;
import org.eclipse.oomph.p2.core.P2Util;
import org.eclipse.oomph.p2.core.Profile;
import org.eclipse.oomph.p2.core.ProfileTransaction;
import org.eclipse.oomph.util.Confirmer;
import org.eclipse.oomph.util.Confirmer.Confirmation;
import org.eclipse.oomph.util.ObjectUtil;
import org.eclipse.oomph.util.Pair;
import org.eclipse.oomph.util.ReflectUtil;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.EcoreUtil.EqualityHelper;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.internal.p2.artifact.repository.simple.SimpleArtifactRepository;
import org.eclipse.equinox.internal.p2.director.SimplePlanner;
import org.eclipse.equinox.internal.p2.engine.InstallableUnitOperand;
import org.eclipse.equinox.internal.p2.engine.InstallableUnitPropertyOperand;
import org.eclipse.equinox.internal.p2.engine.Operand;
import org.eclipse.equinox.internal.p2.engine.PropertyOperand;
import org.eclipse.equinox.internal.p2.engine.ProvisioningPlan;
import org.eclipse.equinox.internal.p2.metadata.IRequiredCapability;
import org.eclipse.equinox.internal.p2.touchpoint.natives.BackupStore;
import org.eclipse.equinox.internal.p2.touchpoint.natives.IBackupStore;
import org.eclipse.equinox.internal.p2.touchpoint.natives.NativeTouchpoint;
import org.eclipse.equinox.internal.provisional.p2.director.PlanExecutionHelper;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.UIServices;
import org.eclipse.equinox.p2.engine.IEngine;
import org.eclipse.equinox.p2.engine.IPhaseSet;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import org.eclipse.equinox.p2.engine.query.UserVisibleRootQuery;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IProvidedCapability;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.metadata.VersionRange;
import org.eclipse.equinox.p2.planner.IPlanner;
import org.eclipse.equinox.p2.planner.IProfileChangeRequest;
import org.eclipse.equinox.p2.planner.ProfileInclusionRules;
import org.eclipse.equinox.p2.query.IQuery;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.osgi.service.datalocation.Location;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Eike Stepper
 */
@SuppressWarnings("restriction")
public class ProfileTransactionImpl implements ProfileTransaction
{
  private static final String OSGI_RESOLVER_USES_MODE = "osgi.resolver.usesMode";

  private static final String SOURCE_IU_ID = "org.eclipse.oomph.p2.source.container"; //$NON-NLS-1$

  private static final IRequirement BUNDLE_REQUIREMENT = MetadataFactory.createRequirement(
      "org.eclipse.equinox.p2.eclipse.type", "bundle", null, null, false, false, false); //$NON-NLS-1$ //$NON-NLS-2$

  private static final Set<String> IMMUTABLE_PROPERTIES = new HashSet<String>(Arrays.asList(Profile.PROP_INSTALL_FEATURES, Profile.PROP_INSTALL_FOLDER,
      Profile.PROP_CACHE, Profile.PROP_PROFILE_TYPE, Profile.PROP_PROFILE_DEFINITION));

  private final Profile profile;

  private final ProfileDefinition profileDefinition;

  private final ProfileDefinition cleanProfileDefinition;

  private final Map<String, String> profileProperties = new HashMap<String, String>();

  private final Map<String, String> cleanProfileProperties = new HashMap<String, String>();

  private final Map<IUPropertyKey, String> iuProperties = new HashMap<IUPropertyKey, String>();

  private final Map<IUPropertyKey, String> cleanIUProperties = new HashMap<IUPropertyKey, String>();

  private boolean removeAll;

  private boolean mirrors;

  private boolean committed;

  public ProfileTransactionImpl(Profile profile)
  {
    this.profile = profile;

    cleanProfileDefinition = profile.getDefinition();
    profileDefinition = EcoreUtil.copy(cleanProfileDefinition);

    cleanProfileProperties.putAll(profile.getProperties());
    cleanProfileProperties.remove(Profile.PROP_INSTALL_FOLDER);
    cleanProfileProperties.remove(Profile.PROP_CACHE);
    cleanProfileProperties.remove(Profile.PROP_PROFILE_DEFINITION);
    profileProperties.putAll(cleanProfileProperties);

    for (IInstallableUnit iu : P2Util.asIterable(profile.query(QueryUtil.createIUAnyQuery(), new NullProgressMonitor())))
    {
      Map<String, String> properties = profile.getInstallableUnitProperties(iu);
      if (!properties.isEmpty())
      {
        for (Map.Entry<String, String> property : properties.entrySet())
        {
          String key = property.getKey();
          String value = property.getValue();
          cleanIUProperties.put(new IUPropertyKey(iu, key), value);
        }
      }
    }

    iuProperties.putAll(cleanIUProperties);

    mirrors = SimpleArtifactRepository.MIRRORS_ENABLED;
  }

  public Profile getProfile()
  {
    return profile;
  }

  public ProfileDefinition getProfileDefinition()
  {
    return profileDefinition;
  }

  public String getProfileProperty(String key)
  {
    return profileProperties.get(key);
  }

  public ProfileTransaction setProfileProperty(String key, String value)
  {
    if (IMMUTABLE_PROPERTIES.contains(key) && !ObjectUtil.equals(profileProperties.get(key), value))
    {
      throw new IllegalArgumentException("Property is immutable: " + key);
    }

    if (value != null)
    {
      profileProperties.put(key, value);
    }
    else
    {
      profileProperties.remove(key);
    }

    return this;
  }

  public ProfileTransaction removeProfileProperty(String key)
  {
    return setProfileProperty(key, null);
  }

  public String getInstallableUnitProperty(IInstallableUnit iu, String key)
  {
    return iuProperties.get(new IUPropertyKey(iu, key));
  }

  public ProfileTransaction setInstallableUnitProperty(IInstallableUnit iu, String key, String value)
  {
    IUPropertyKey propertyKey = new IUPropertyKey(iu, key);
    if (value != null)
    {
      iuProperties.put(propertyKey, value);
    }
    else
    {
      iuProperties.remove(propertyKey);
    }

    return this;
  }

  public ProfileTransaction removeInstallableUnitProperty(IInstallableUnit iu, String key)
  {
    return setInstallableUnitProperty(iu, key, null);
  }

  public boolean isRemoveExistingInstallableUnits()
  {
    return removeAll;
  }

  public ProfileTransaction setRemoveExistingInstallableUnits(boolean removeAll)
  {
    this.removeAll = removeAll;
    return this;
  }

  public boolean isMirrors()
  {
    return mirrors;
  }

  public ProfileTransaction setMirrors(boolean mirrors)
  {
    this.mirrors = mirrors;
    return this;
  }

  public boolean isDirty()
  {
    if (removeAll)
    {
      return true;
    }

    if (!profileProperties.equals(cleanProfileProperties))
    {
      return true;
    }

    if (!iuProperties.equals(cleanIUProperties))
    {
      return true;
    }

    return isProfileDefinitionChanged();
  }

  private boolean isProfileDefinitionChanged()
  {
    if (profileDefinition.isIncludeSourceBundles() != cleanProfileDefinition.isIncludeSourceBundles())
    {
      return true;
    }

    EqualityHelper equalityHelper = new EqualityHelper();
    if (!equals(equalityHelper, profileDefinition.getRequirements(), cleanProfileDefinition.getRequirements()))
    {
      return true;
    }

    if (!equals(equalityHelper, profileDefinition.getRepositories(), cleanProfileDefinition.getRepositories()))
    {
      return true;
    }

    return false;
  }

  public boolean commit() throws CoreException
  {
    return commit(null, null);
  }

  public boolean commit(IProgressMonitor monitor) throws CoreException
  {
    return commit(null, monitor);
  }

  public boolean commit(CommitContext commitContext, IProgressMonitor monitor) throws CoreException
  {
    if (!committed)
    {
      committed = true;

      Resolution resolution = resolve(commitContext, monitor);
      if (resolution != null)
      {
        return resolution.commit(monitor);
      }
    }

    return false;
  }

  public Resolution resolve(IProgressMonitor monitor) throws CoreException
  {
    return resolve(null, monitor);
  }

  public Resolution resolve(CommitContext commitContext, IProgressMonitor monitor) throws CoreException
  {
    final CommitContext context = commitContext == null ? new CommitContext() : commitContext;
    if (monitor == null)
    {
      monitor = new NullProgressMonitor();
    }

    final Agent agent = profile.getAgent();
    final List<Runnable> cleanup = new ArrayList<Runnable>();

    try
    {
      initMirrors(cleanup);

      List<IMetadataRepository> metadataRepositories = new ArrayList<IMetadataRepository>();
      Set<URI> artifactURIs = new HashSet<URI>();
      URI[] metadataURIs = collectRepositories(metadataRepositories, artifactURIs, cleanup, monitor);

      final ProfileImpl profileImpl = (ProfileImpl)profile;
      final IProfile delegate = profileImpl.getDelegate();
      final long timestamp = delegate.getTimestamp();

      IPlanner planner = agent.getPlanner();
      IProfileChangeRequest profileChangeRequest = planner.createChangeRequest(delegate);
      IInstallableUnit rootIU = adjustProfileChangeRequest(profileChangeRequest, monitor);

      final ProvisioningContext provisioningContext = context.createProvisioningContext(this, profileChangeRequest);
      provisioningContext.setMetadataRepositories(metadataURIs);
      provisioningContext.setArtifactRepositories(artifactURIs.toArray(new URI[artifactURIs.size()]));

      IQueryable<IInstallableUnit> metadata = provisioningContext.getMetadata(monitor);

      final IProvisioningPlan provisioningPlan = planner.getProvisioningPlan(profileChangeRequest, provisioningContext, monitor);
      P2CorePlugin.INSTANCE.coreException(provisioningPlan.getStatus());

      IQueryable<IInstallableUnit> futureState = provisioningPlan.getFutureState();
      for (IRequirement requirement : rootIU.getRequirements())
      {
        if (requirement instanceof IRequiredCapability)
        {
          IRequiredCapability requiredCapability = (IRequiredCapability)requirement;
          for (IInstallableUnit installableUnit : P2Util.asIterable(futureState.query(
              QueryUtil.createIUQuery(requiredCapability.getName(), requiredCapability.getRange()), null)))
          {
            provisioningPlan.setInstallableUnitProfileProperty(installableUnit, Profile.PROP_PROFILE_ROOT_IU, Boolean.TRUE.toString());
            provisioningPlan.setInstallableUnitProfileProperty(installableUnit, SimplePlanner.INCLUSION_RULES,
                ProfileInclusionRules.createStrictInclusionRule(installableUnit));
          }
        }
      }

      if (profileDefinition.isIncludeSourceBundles())
      {
        IInstallableUnit sourceContainerIU = generateSourceContainerIU(provisioningPlan, metadata, monitor);
        provisioningPlan.addInstallableUnit(sourceContainerIU);
        provisioningPlan.setInstallableUnitProfileProperty(sourceContainerIU, Profile.PROP_PROFILE_ROOT_IU, Boolean.TRUE.toString());
      }

      Map<IInstallableUnit, CommitContext.DeltaType> iuDeltas = new HashMap<IInstallableUnit, CommitContext.DeltaType>();
      Map<IInstallableUnit, Map<String, Pair<Object, Object>>> propertyDeltas = new HashMap<IInstallableUnit, Map<String, Pair<Object, Object>>>();
      computeOperandDeltas(provisioningPlan, iuDeltas, propertyDeltas);

      if (!context.handleProvisioningPlan(provisioningPlan, iuDeltas, propertyDeltas, metadataRepositories))
      {
        return null;
      }

      if (iuDeltas.isEmpty() && propertyDeltas.isEmpty())
      {
        return null;
      }

      return new Resolution()
      {
        public ProfileTransaction getProfileTransaction()
        {
          return ProfileTransactionImpl.this;
        }

        public IProvisioningPlan getProvisioningPlan()
        {
          return provisioningPlan;
        }

        public boolean commit(IProgressMonitor monitor) throws CoreException
        {
          final String oldUsesMode = System.setProperty(OSGI_RESOLVER_USES_MODE, "ignore");
          cleanup.add(new Runnable()
          {
            public void run()
            {
              if (oldUsesMode == null)
              {
                System.clearProperty(OSGI_RESOLVER_USES_MODE);
              }
              else
              {
                System.setProperty(OSGI_RESOLVER_USES_MODE, oldUsesMode);
              }
            }
          });

          try
          {
            IPhaseSet phaseSet = context.getPhaseSet(ProfileTransactionImpl.this);

            initUnsignedContentConfirmer(context, agent, cleanup);

            IEngine engine = agent.getEngine();
            ensureSameBackupDevice(provisioningPlan);

            IStatus status = PlanExecutionHelper.executePlan(provisioningPlan, engine, phaseSet, provisioningContext, monitor);

            context.handleExecutionResult(status);
            P2CorePlugin.INSTANCE.coreException(status);

            profileImpl.setDefinition(profileDefinition);
            return delegate.getTimestamp() != timestamp;
          }
          finally
          {
            cleanup(cleanup);
          }
        }

        public void rollback()
        {
          cleanup(cleanup);
        }
      };
    }
    catch (Throwable t)
    {
      cleanup(cleanup);
      P2CorePlugin.INSTANCE.coreException(t);
      return null;
    }
  }

  private void computeOperandDeltas(final IProvisioningPlan provisioningPlan, Map<IInstallableUnit, CommitContext.DeltaType> iuDeltas,
      Map<IInstallableUnit, Map<String, Pair<Object, Object>>> propertyDeltas)
  {
    // Undo (remove) the addition of our artificial root IU and compute the effective deltas (to remove redundancies in the operands).
    Field operandsField = ReflectUtil.getField(ProvisioningPlan.class, "operands");
    @SuppressWarnings("unchecked")
    List<Operand> operands = (List<Operand>)ReflectUtil.getValue(operandsField, provisioningPlan);
    for (Iterator<Operand> it = operands.iterator(); it.hasNext();)
    {
      Operand operand = it.next();
      if (operand instanceof InstallableUnitOperand)
      {
        InstallableUnitOperand iuOperand = (InstallableUnitOperand)operand;
        IInstallableUnit first = iuOperand.first();
        IInstallableUnit second = iuOperand.second();
        if (first == null)
        {
          if (second.getId().equals("artificial_root"))
          {
            it.remove();
          }
          else
          {
            iuDeltas.put(second, CommitContext.DeltaType.ADDITION);
          }
        }
        else
        {
          iuDeltas.put(first, CommitContext.DeltaType.REMOVAL);
          if (second != null)
          {
            iuDeltas.put(second, CommitContext.DeltaType.ADDITION);
          }
        }
      }
      else if (operand instanceof InstallableUnitPropertyOperand)
      {
        InstallableUnitPropertyOperand iuPropertyOperand = (InstallableUnitPropertyOperand)operand;
        IInstallableUnit operandIU = iuPropertyOperand.getInstallableUnit();
        if (operandIU.getId().equals("artificial_root"))
        {
          it.remove();
        }
        else
        {
          Object first = iuPropertyOperand.first();
          Object second = iuPropertyOperand.second();
          String key = iuPropertyOperand.getKey();
          populatePropertyDeltas(operandIU, first, second, key, propertyDeltas);
        }
      }
      else if (operand instanceof PropertyOperand)
      {
        PropertyOperand propertyOperand = (PropertyOperand)operand;
        Object first = propertyOperand.first();
        Object second = propertyOperand.second();
        String key = propertyOperand.getKey();
        populatePropertyDeltas(null, first, second, key, propertyDeltas);
      }
    }

    for (Iterator<Map.Entry<IInstallableUnit, Map<String, Pair<Object, Object>>>> it = propertyDeltas.entrySet().iterator(); it.hasNext();)
    {
      Map.Entry<IInstallableUnit, Map<String, Pair<Object, Object>>> entry = it.next();
      Set<Map.Entry<String, Pair<Object, Object>>> properties = entry.getValue().entrySet();
      for (Iterator<Map.Entry<String, Pair<Object, Object>>> it2 = properties.iterator(); it2.hasNext();)
      {
        Map.Entry<String, Pair<Object, Object>> property = it2.next();
        Pair<Object, Object> pair = property.getValue();
        if (ObjectUtil.equals(pair.getElement1(), pair.getElement2()))
        {
          it2.remove();
        }
      }

      if (properties.isEmpty())
      {
        it.remove();
      }
    }
  }

  private void populatePropertyDeltas(IInstallableUnit operandIU, Object first, Object second, String key,
      Map<IInstallableUnit, Map<String, Pair<Object, Object>>> propertyDeltas)
  {
    Map<String, Pair<Object, Object>> propertyDelta = propertyDeltas.get(operandIU);
    if (propertyDelta == null)
    {
      propertyDelta = new HashMap<String, Pair<Object, Object>>();
      propertyDelta.put(key, new Pair<Object, Object>(first, second));
      propertyDeltas.put(operandIU, propertyDelta);
    }
    else
    {
      Pair<Object, Object> pair = propertyDelta.get(key);
      if (pair == null)
      {
        propertyDelta.put(key, new Pair<Object, Object>(first, second));
      }
      else
      {
        pair.setElement2(second);
      }
    }
  }

  private void initMirrors(final List<Runnable> cleanup)
  {
    final boolean wasMirrors = SimpleArtifactRepository.MIRRORS_ENABLED;
    if (mirrors != wasMirrors)
    {
      try
      {
        final Field mirrorsEnabledField = ReflectUtil.getField(SimpleArtifactRepository.class, "MIRRORS_ENABLED");
        ReflectUtil.setValue(mirrorsEnabledField, null, mirrors, true);

        cleanup.add(new Runnable()
        {
          public void run()
          {
            try
            {
              ReflectUtil.setValue(mirrorsEnabledField, null, wasMirrors, true);
            }
            catch (Throwable ex)
            {
              // Ignore
            }
          }
        });
      }
      catch (Throwable ex)
      {
        // Ignore
      }
    }
  }

  private void initUnsignedContentConfirmer(final CommitContext context, final Agent agent, final List<Runnable> cleanup)
  {
    final Confirmer unsignedContentConfirmer = context.getUnsignedContentConfirmer();
    if (unsignedContentConfirmer != null)
    {
      final IProvisioningAgent provisioningAgent = agent.getProvisioningAgent();
      final UIServices oldUIServices = (UIServices)provisioningAgent.getService(UIServices.SERVICE_NAME);
      final UIServices newUIServices = new UIServices()
      {
        @Override
        public AuthenticationInfo getUsernamePassword(String location)
        {
          if (oldUIServices != null)
          {
            return oldUIServices.getUsernamePassword(location);
          }

          return null;
        }

        @Override
        public AuthenticationInfo getUsernamePassword(String location, AuthenticationInfo previousInfo)
        {
          if (oldUIServices != null)
          {
            return oldUIServices.getUsernamePassword(location, previousInfo);
          }

          return null;
        }

        @Override
        public TrustInfo getTrustInfo(Certificate[][] untrustedChains, String[] unsignedDetail)
        {
          if (unsignedDetail != null && unsignedDetail.length != 0)
          {
            Confirmation confirmation = unsignedContentConfirmer.confirm(true, unsignedDetail);
            if (!confirmation.isConfirmed())
            {
              return new TrustInfo(new Certificate[0], false, false);
            }

            // We've checked trust already; prevent oldUIServices to check it again.
            unsignedDetail = null;
          }

          if (oldUIServices != null)
          {
            return oldUIServices.getTrustInfo(untrustedChains, unsignedDetail);
          }

          // The rest is copied from org.eclipse.equinox.internal.p2.director.app.DirectorApplication.AvoidTrustPromptService
          final Certificate[] trusted;
          if (untrustedChains == null)
          {
            trusted = null;
          }
          else
          {
            trusted = new Certificate[untrustedChains.length];
            for (int i = 0; i < untrustedChains.length; i++)
            {
              trusted[i] = untrustedChains[i][0];
            }
          }

          return new TrustInfo(trusted, false, true);
        }
      };

      provisioningAgent.registerService(UIServices.SERVICE_NAME, newUIServices);

      cleanup.add(new Runnable()
      {
        public void run()
        {
          provisioningAgent.unregisterService(UIServices.SERVICE_NAME, newUIServices);
          if (oldUIServices != null)
          {
            provisioningAgent.registerService(UIServices.SERVICE_NAME, oldUIServices);
          }
        }
      });
    }
  }

  private URI[] collectRepositories(List<IMetadataRepository> metadataRepositories, Set<URI> artifactURIs, List<Runnable> cleanup, IProgressMonitor monitor)
      throws CoreException
  {
    Agent agent = profile.getAgent();
    final IMetadataRepositoryManager manager = agent.getMetadataRepositoryManager();
    Set<String> knownRepositories = P2Util.getKnownRepositories(manager);

    EList<Repository> repositories = profileDefinition.getRepositories();
    URI[] metadataURIs = new URI[repositories.size()];

    for (int i = 0; i < metadataURIs.length; i++)
    {
      P2CorePlugin.checkCancelation(monitor);

      try
      {
        Repository repository = repositories.get(i);
        String url = repository.getURL();
        final URI uri = new URI(url);

        if (!knownRepositories.contains(url))
        {
          cleanup.add(new Runnable()
          {
            public void run()
            {
              manager.removeRepository(uri);
            }
          });
        }

        IMetadataRepository metadataRepository = manager.loadRepository(uri, monitor);
        metadataRepositories.add(metadataRepository);

        metadataURIs[i] = uri;
        artifactURIs.add(uri);
      }
      catch (OperationCanceledException ex)
      {
        throw ex;
      }
      catch (CoreException ex)
      {
        throw ex;
      }
      catch (Exception ex)
      {
        throw new P2Exception(ex);
      }
    }

    for (BundlePool bundlePool : agent.getAgentManager().getBundlePools())
    {
      P2CorePlugin.checkCancelation(monitor);
      File location = bundlePool.getLocation();

      if (!isLocationWithCrippledICU(location))
      {
        artifactURIs.add(location.toURI());
      }
    }

    return metadataURIs;
  }

  private boolean isLocationWithCrippledICU(File location)
  {
    File plugins = new File(location, "plugins");
    if (plugins.isDirectory())
    {
      File[] files = plugins.listFiles(new FilenameFilter()
      {
        public boolean accept(File dir, String name)
        {
          return name.startsWith("com.ibm.icu_") && name.endsWith(".jar");
        }
      });

      if (files != null)
      {
        for (File file : files)
        {
          if (file.length() < 1000000)
          {
            return true;
          }
        }
      }
    }

    return false;
  }

  private IInstallableUnit adjustProfileChangeRequest(final IProfileChangeRequest request, IProgressMonitor monitor) throws CoreException
  {
    InstallableUnitDescription rootDescription = new InstallableUnitDescription();
    rootDescription.setId("artificial_root");
    rootDescription.setVersion(Version.createOSGi(1, 0, 0, "v" + System.currentTimeMillis()));
    rootDescription.setSingleton(true);
    rootDescription.setArtifacts(new IArtifactKey[0]);
    rootDescription.setProperty(InstallableUnitDescription.PROP_TYPE_GROUP, Boolean.TRUE.toString());
    rootDescription.setCapabilities(new IProvidedCapability[] { MetadataFactory.createProvidedCapability(IInstallableUnit.NAMESPACE_IU_ID,
        rootDescription.getId(), rootDescription.getVersion()) });
    List<IRequirement> rootRequirements = new ArrayList<IRequirement>();

    Map<String, IInstallableUnit> rootIUs = new HashMap<String, IInstallableUnit>();
    for (IInstallableUnit rootIU : P2Util.asIterable(profile.query(new UserVisibleRootQuery(), null)))
    {
      if (!removeAll)
      {
        String id = rootIU.getId();
        rootIUs.put(id, rootIU);

        VersionRange versionRange = getCleanVersionRange(rootIU);
        IRequirement rootRequirement = MetadataFactory.createRequirement(IInstallableUnit.NAMESPACE_IU_ID, id, versionRange, null, false, false);
        rootRequirements.add(rootRequirement);
      }

      request.remove(rootIU);
    }

    MultiStatus status = new MultiStatus(P2CorePlugin.INSTANCE.getSymbolicName(), 0, "Profile could not be changed", null);

    for (Requirement requirement : profileDefinition.getRequirements())
    {
      P2CorePlugin.checkCancelation(monitor);

      String namespace = requirement.getNamespace();
      String name = requirement.getName();
      VersionRange versionRange = requirement.getVersionRange();
      boolean optional = requirement.isOptional();

      IRequirement rootRequirement = MetadataFactory.createRequirement(namespace, name, versionRange, null, optional, false);
      rootRequirements.add(rootRequirement);
    }

    rootDescription.setRequirements(rootRequirements.toArray(new IRequirement[rootRequirements.size()]));

    IInstallableUnit rootUnit = MetadataFactory.createInstallableUnit(rootDescription);
    request.add(rootUnit);
    request.setInstallableUnitProfileProperty(rootUnit, Profile.PROP_PROFILE_ROOT_IU, Boolean.TRUE.toString());

    P2CorePlugin.INSTANCE.coreException(status);

    if (isProfileDefinitionChanged())
    {
      request.setProfileProperty(Profile.PROP_PROFILE_DEFINITION, ProfileImpl.definitionToXML(profileDefinition));
    }

    compare(cleanProfileProperties, profileProperties, new CompareHandler<String>()
    {
      public void handleAddition(String key, String value)
      {
        if (!IMMUTABLE_PROPERTIES.contains(key))
        {
          request.setProfileProperty(key, value);
        }
      }

      public void handleRemoval(String key)
      {
        if (!IMMUTABLE_PROPERTIES.contains(key))
        {
          request.removeProfileProperty(key);
        }
      }
    });

    compare(cleanIUProperties, iuProperties, new CompareHandler<IUPropertyKey>()
    {
      public void handleAddition(IUPropertyKey key, String value)
      {
        request.setInstallableUnitProfileProperty(key.getInstallableUnit(), key.getPropertyKey(), value);
      }

      public void handleRemoval(IUPropertyKey key)
      {
        request.removeInstallableUnitProfileProperty(key.getInstallableUnit(), key.getPropertyKey());
      }
    });

    return rootUnit;
  }

  private VersionRange getCleanVersionRange(IInstallableUnit rootIU)
  {
    String id = rootIU.getId();
    Version version = rootIU.getVersion();
    for (Requirement requirement : cleanProfileDefinition.getRequirements())
    {
      if (requirement.getName().equals(id))
      {
        VersionRange versionRange = requirement.getVersionRange();
        if (versionRange == null || versionRange.isIncluded(version))
        {
          return versionRange;
        }
      }
    }

    return new VersionRange(version.toString());
  }

  private void ensureSameBackupDevice(final IProvisioningPlan provisioningPlan) throws CoreException
  {
    // This is to handle the special case in Windows where the backup store tries to move the *.exe to a different device.
    // This fails when the *.exe is currently in use.
    // The strategy then copies the file instead and then to delete it, but that always fails.
    // If the backup store is on the same device, the move is successful and the *.exe can be successfully updated.
    // See https://bugs.eclipse.org/bugs/show_bug.cgi?id=427148 for more details.
    if (profile.isCurrent() && Platform.OS_WIN32.equals(Platform.getOS()))
    {
      try
      {
        Location location = Platform.getInstallLocation();
        org.eclipse.emf.common.util.URI installationLocation = org.eclipse.emf.common.util.URI.createURI(FileLocator.resolve(location.getURL()).toString());
        org.eclipse.emf.common.util.URI tempDir = org.eclipse.emf.common.util.URI.createFileURI(System.getProperty("java.io.tmpdir"));
        if (!ObjectUtil.equals(installationLocation.device(), tempDir.device()))
        {
          Field field = ReflectUtil.getField(NativeTouchpoint.class, "backups");
          @SuppressWarnings("unchecked")
          Map<IProfile, IBackupStore> backups = (Map<IProfile, IBackupStore>)ReflectUtil.getValue(field, null);
          final File localTempFolder = new File(installationLocation.toFileString(), "backup");
          final IProfile planProfile = provisioningPlan.getProfile();
          backups.put(planProfile, new IBackupStore()
          {
            private BackupStore delegate;

            public boolean backup(File file) throws IOException
            {
              loadDelegate();
              return delegate.backup(file);
            }

            public boolean backupDirectory(File file) throws IOException
            {
              loadDelegate();
              return delegate.backupDirectory(file);
            }

            public void discard()
            {
              if (delegate == null)
              {
                return;
              }
              delegate.discard();
            }

            public void restore() throws IOException
            {
              if (delegate == null)
              {
                return;
              }
              delegate.restore();
            }

            private void loadDelegate()
            {
              if (delegate != null)
              {
                return;
              }
              delegate = new BackupStore(localTempFolder, NativeTouchpoint.escape(planProfile.getProfileId()));
            }

            public String getBackupName()
            {
              loadDelegate();
              return delegate.getBackupName();
            }

            public boolean backupCopy(File file) throws IOException
            {
              loadDelegate();
              return delegate.backupCopy(file);
            }

            public void backupCopyAll(File file) throws IOException
            {
              loadDelegate();
              delegate.backupCopyAll(file);
            }

            public void backupAll(File file) throws IOException
            {
              loadDelegate();
              delegate.backupAll(file);
            }
          });
        }
      }
      catch (IOException ex)
      {
        P2CorePlugin.INSTANCE.coreException(ex);
      }
    }
  }

  private static void cleanup(List<Runnable> cleanup)
  {
    for (Runnable runnable : cleanup)
    {
      try
      {
        runnable.run();
      }
      catch (Throwable t)
      {
        P2CorePlugin.INSTANCE.log(t);
      }
    }

    cleanup.clear();
  }

  private static IInstallableUnit generateSourceContainerIU(IProvisioningPlan provisioningPlan, IQueryable<IInstallableUnit> metadata, IProgressMonitor monitor)
  {
    // Create and return an IU that has optional and greedy requirements on all source bundles
    // related to bundle IUs in the profile
    List<IRequirement> requirements = new ArrayList<IRequirement>();

    IQueryResult<IInstallableUnit> ius = provisioningPlan.getFutureState().query(QueryUtil.createIUAnyQuery(), monitor);
    for (IInstallableUnit iu : P2Util.asIterable(ius))
    {
      P2CorePlugin.checkCancelation(monitor);

      // TODO What about source features?
      if (iu.satisfies(BUNDLE_REQUIREMENT))
      {
        String id = iu.getId() + ".source";
        Version version = iu.getVersion();
        VersionRange versionRange = new VersionRange(version, true, version, true);

        IInstallableUnit sourceIU = queryInstallableUnit(metadata, id, versionRange, monitor);
        if (sourceIU != null)
        {
          provisioningPlan.addInstallableUnit(sourceIU);

          IRequirement sourceRequirement = MetadataFactory.createRequirement("osgi.bundle", id, versionRange, null, true, false, true);
          requirements.add(sourceRequirement);
        }
      }
    }

    Version sourceContainerIUVersion = getSourceContainerIUVersion(provisioningPlan.getProfile(), monitor);
    IProvidedCapability capability = MetadataFactory.createProvidedCapability(IInstallableUnit.NAMESPACE_IU_ID, SOURCE_IU_ID, sourceContainerIUVersion);

    InstallableUnitDescription sourceIUDescription = new MetadataFactory.InstallableUnitDescription();
    sourceIUDescription.setSingleton(true);
    sourceIUDescription.setId(SOURCE_IU_ID);
    sourceIUDescription.setVersion(sourceContainerIUVersion);
    sourceIUDescription.addRequirements(requirements);
    sourceIUDescription.setCapabilities(new IProvidedCapability[] { capability });

    return MetadataFactory.createInstallableUnit(sourceIUDescription);
  }

  private static Version getSourceContainerIUVersion(IProfile profile, IProgressMonitor monitor)
  {
    IQuery<IInstallableUnit> query = QueryUtil.createIUQuery(SOURCE_IU_ID);
    IQueryResult<IInstallableUnit> result = profile.query(query, monitor);
    if (result.isEmpty())
    {
      return Version.createOSGi(1, 0, 0);
    }

    IInstallableUnit currentSourceIU = result.iterator().next();
    Integer major = (Integer)currentSourceIU.getVersion().getSegment(0);
    return Version.createOSGi(major.intValue() + 1, 0, 0);
  }

  private static IInstallableUnit queryInstallableUnit(IQueryable<IInstallableUnit> metadata, String id, VersionRange versionRange, IProgressMonitor monitor)
  {
    IQuery<IInstallableUnit> iuQuery = QueryUtil.createIUQuery(id, versionRange);
    IQuery<IInstallableUnit> latestQuery = QueryUtil.createLatestQuery(iuQuery);

    Iterator<IInstallableUnit> iterator = metadata.query(latestQuery, monitor).iterator();
    if (iterator.hasNext())
    {
      return iterator.next();
    }

    return null;
  }

  private static <T extends EObject> boolean equals(EqualityHelper equalityHelper, Collection<T> c1, Collection<T> c2)
  {
    for (T o : c1)
    {
      if (!contains(equalityHelper, c2, o))
      {
        return false;
      }
    }

    for (T o : c2)
    {
      if (!contains(equalityHelper, c1, o))
      {
        return false;
      }
    }

    return true;
  }

  private static <T extends EObject> boolean contains(EqualityHelper equalityHelper, Collection<T> c, T object)
  {
    for (T o : c)
    {
      if (equalityHelper.equals(object, o))
      {
        return true;
      }
    }

    return false;
  }

  private static <K> void compare(Map<K, String> clean, Map<K, String> dirty, CompareHandler<K> handler)
  {
    for (Map.Entry<K, String> entry : dirty.entrySet())
    {
      K key = entry.getKey();
      String dirtyValue = entry.getValue();
      String cleanValue = clean.get(key);

      if (cleanValue == null || !cleanValue.equals(dirtyValue))
      {
        handler.handleAddition(key, dirtyValue);
      }
    }

    for (Map.Entry<K, String> entry : clean.entrySet())
    {
      K key = entry.getKey();
      if (!dirty.containsKey(key))
      {
        handler.handleRemoval(key);
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  public interface CompareHandler<K>
  {
    public void handleAddition(K key, String value);

    public void handleRemoval(K key);
  }

  /**
   * @author Eike Stepper
   */
  private static final class IUPropertyKey
  {
    private static final int PRIME = 31;

    private final IInstallableUnit iu;

    private final String propertyKey;

    private final int hashCode;

    public IUPropertyKey(IInstallableUnit iu, String propertyKey)
    {
      this.iu = iu;
      this.propertyKey = propertyKey;
      hashCode = PRIME * (PRIME + iu.hashCode()) + propertyKey.hashCode();
    }

    public IInstallableUnit getInstallableUnit()
    {
      return iu;
    }

    public String getPropertyKey()
    {
      return propertyKey;
    }

    @Override
    public int hashCode()
    {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj)
    {
      if (this == obj)
      {
        return true;
      }

      IUPropertyKey other = (IUPropertyKey)obj;
      if (!iu.equals(other.iu))
      {
        return false;
      }

      if (!propertyKey.equals(other.propertyKey))
      {
        return false;
      }

      return true;
    }

    @Override
    public String toString()
    {
      return iu.toString() + " / " + propertyKey;
    }
  }
}
