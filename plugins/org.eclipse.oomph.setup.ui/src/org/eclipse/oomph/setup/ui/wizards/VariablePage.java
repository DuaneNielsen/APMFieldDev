/*
 * Copyright (c) 2014, 2015 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 *    Ericsson AB (Julian Enoch) - Bug 434525 - Allow prompted variables to be pre-populated
 */
package org.eclipse.oomph.setup.ui.wizards;

import org.eclipse.oomph.base.Annotation;
import org.eclipse.oomph.base.util.BaseUtil;
import org.eclipse.oomph.internal.setup.SetupPrompter;
import org.eclipse.oomph.internal.ui.AccessUtil;
import org.eclipse.oomph.preferences.util.PreferencesUtil;
import org.eclipse.oomph.setup.AnnotationConstants;
import org.eclipse.oomph.setup.Installation;
import org.eclipse.oomph.setup.SetupTask;
import org.eclipse.oomph.setup.SetupTaskContext;
import org.eclipse.oomph.setup.Trigger;
import org.eclipse.oomph.setup.User;
import org.eclipse.oomph.setup.VariableChoice;
import org.eclipse.oomph.setup.VariableTask;
import org.eclipse.oomph.setup.Workspace;
import org.eclipse.oomph.setup.internal.core.SetupContext;
import org.eclipse.oomph.setup.internal.core.SetupTaskPerformer;
import org.eclipse.oomph.setup.internal.core.util.Authenticator;
import org.eclipse.oomph.setup.internal.core.util.SetupCoreUtil;
import org.eclipse.oomph.setup.ui.PropertyField;
import org.eclipse.oomph.setup.ui.PropertyField.AuthenticatedField;
import org.eclipse.oomph.setup.ui.PropertyField.ValueListener;
import org.eclipse.oomph.setup.ui.SetupUIPlugin;
import org.eclipse.oomph.setup.ui.wizards.SetupWizard.IndexLoader;
import org.eclipse.oomph.setup.util.StringExpander;
import org.eclipse.oomph.ui.ButtonBar;
import org.eclipse.oomph.ui.UICallback;
import org.eclipse.oomph.ui.UIUtil;
import org.eclipse.oomph.util.CollectionUtil;
import org.eclipse.oomph.util.OS;
import org.eclipse.oomph.util.StringUtil;
import org.eclipse.oomph.util.UserCallback;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;

import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Eike Stepper
 */
public class VariablePage extends SetupWizardPage implements SetupPrompter
{
  private static final URI INSTALLATION_ID_URI = URI.createURI("#~installation.id");

  private static final URI WORKSPACE_ID_URI = URI.createURI("#~workspace.id");

  private Composite composite;

  private ScrolledComposite scrolledComposite;

  private final FieldHolderManager manager = new FieldHolderManager();

  private final Set<String> unusedVariables = new HashSet<String>();

  private boolean prompted;

  private boolean fullPrompt;

  private boolean updating;

  private Set<SetupTaskPerformer> incompletePerformers = new LinkedHashSet<SetupTaskPerformer>();

  private Set<SetupTaskPerformer> allPromptedPerfomers = new LinkedHashSet<SetupTaskPerformer>();

  private SetupTaskPerformer performer;

  private Control focusControl;

  private SetupContext originalContext;

  private boolean save = true;

  private boolean defaultsSet;

  private UserCallback userCallback;

  private FocusListener focusListener = new FocusAdapter()
  {
    @Override
    public void focusGained(FocusEvent e)
    {
      focusControl = (Control)e.widget;
    }
  };

  public VariablePage()
  {
    super("VariablePage");
    setTitle("Variables");
    setDescription("Enter values for the required variables.");
  }

  @Override
  protected Control createUI(Composite parent)
  {
    Composite mainComposite = new Composite(parent, SWT.NONE);
    mainComposite.setLayout(UIUtil.createGridLayout(1));
    mainComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

    scrolledComposite = new ScrolledComposite(mainComposite, SWT.VERTICAL);
    scrolledComposite.setExpandHorizontal(true);
    scrolledComposite.setExpandVertical(true);
    scrolledComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

    GridLayout layout = UIUtil.createGridLayout(3);
    layout.horizontalSpacing = 10;
    layout.verticalSpacing = 10;

    composite = new Composite(scrolledComposite, SWT.NONE);
    composite.setLayout(layout);
    scrolledComposite.setContent(composite);
    composite.setLayoutData(new GridData(GridData.FILL_BOTH));

    ControlAdapter resizeListener = new ControlAdapter()
    {
      @Override
      public void controlResized(ControlEvent event)
      {
        Point size = composite.computeSize(scrolledComposite.getClientArea().width, SWT.DEFAULT);
        scrolledComposite.setMinSize(size);
      }
    };

    scrolledComposite.addControlListener(resizeListener);
    composite.addControlListener(resizeListener);
    composite.notifyListeners(SWT.Resize, new Event());

    return mainComposite;
  }

  @Override
  protected void createCheckButtons(ButtonBar buttonBar)
  {
    final Button fullPromptButton = buttonBar.addCheckButton("Show all variables", "", false, "fullPrompt");
    fullPrompt = fullPromptButton.getSelection();
    fullPromptButton.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        fullPrompt = fullPromptButton.getSelection();
        UIUtil.asyncExec(getControl(), new Runnable()
        {
          public void run()
          {
            validate();
          }
        });
      }
    });

    AccessUtil.setKey(fullPromptButton, "showAll");
  }

  private void updateFields()
  {
    unusedVariables.clear();

    for (FieldHolder fieldHolder : manager)
    {
      fieldHolder.clear();
    }

    for (SetupTaskPerformer setupTaskPerformer : incompletePerformers.isEmpty()
        ? performer == null ? Collections.<SetupTaskPerformer> emptySet() : Collections.singleton(performer) : incompletePerformers)
    {
      List<VariableTask> variables = setupTaskPerformer.getUnresolvedVariables();
      for (VariableTask variable : variables)
      {
        VariableTask ruleVariable = setupTaskPerformer.getRuleVariable(variable);
        if (ruleVariable == null)
        {
          if (variable.getAnnotation(AnnotationConstants.ANNOTATION_UNDECLARED_VARIABLE) != null)
          {
            String name = variable.getName();
            Trigger trigger = getTrigger();
            boolean isUsedInActualTriggeredTask = false;
            for (SetupTask setupTask : setupTaskPerformer.getTriggeredSetupTasks())
            {
              if (setupTask.getTriggers().contains(trigger) && setupTaskPerformer.isVariableUsed(name, setupTask, true))
              {
                isUsedInActualTriggeredTask = true;
                break;
              }
            }

            if (!isUsedInActualTriggeredTask)
            {
              unusedVariables.add(name);
              continue;
            }
          }

          manager.getFieldHolder(variable, true, false);
        }
        else
        {
          FieldHolder fieldHolder = manager.getFieldHolder(ruleVariable, true, false);
          fieldHolder.add(variable);
          manager.associate(variable, fieldHolder);
        }
      }
    }

    boolean setDefault = false;
    for (FieldHolder fieldHolder : manager)
    {
      if (StringUtil.isEmpty(fieldHolder.getValue()))
      {
        String initialValue = null;
        String initialDefaultValue = null;
        for (VariableTask variable : fieldHolder.getVariables())
        {
          if (initialValue == null)
          {
            String value = variable.getValue();
            if (!StringUtil.isEmpty(value))
            {
              initialValue = value;

              // Check the choices for ones that specify "match choice" annotations.
              for (VariableChoice choice : variable.getChoices())
              {
                Annotation annotation = choice.getAnnotation(AnnotationConstants.ANNOTATION_MATCH_CHOICE);
                if (annotation != null)
                {
                  String choiceValue = choice.getValue();
                  if (choiceValue != null)
                  {
                    // Expand the choice into a pattern where the variables expand to ".*" and the rest of the value is quoted as literal.
                    StringBuffer result = new StringBuffer("\\Q");
                    Matcher matcher = StringExpander.STRING_EXPANSION_PATTERN.matcher(choiceValue);
                    while (matcher.find())
                    {
                      matcher.appendReplacement(result, "\\\\E.*\\\\Q");
                    }

                    matcher.appendTail(result);

                    try
                    {
                      // If the pattern matches, use the value of the choice as the initial value.
                      Pattern pattern = Pattern.compile(result.toString());
                      if (pattern.matcher(value).matches())
                      {
                        initialValue = choiceValue;
                        setDefault = true;
                        break;
                      }
                    }
                    catch (Throwable throwable)
                    {
                      // Ignore.
                    }
                  }
                }
              }
            }
          }

          if (initialDefaultValue == null)
          {
            String defaultValue = variable.getDefaultValue();
            if (!StringUtil.isEmpty(defaultValue))
            {
              initialDefaultValue = defaultValue;
            }
          }
        }

        if (!StringUtil.isEmpty(initialValue))
        {
          fieldHolder.setValue(initialValue);
        }
        else if (!StringUtil.isEmpty(initialDefaultValue))
        {
          setDefault = true;
          fieldHolder.setValue(initialDefaultValue);
        }
        else
        {
          String defaultValue = fieldHolder.getDefaultValue();
          if (!StringUtil.isEmpty(defaultValue))
          {
            setDefault = true;
            fieldHolder.setValue(defaultValue);
          }
        }
      }
    }

    try
    {
      for (FieldHolder fieldHolder : manager)
      {
        updating = true;
        fieldHolder.update();
      }
    }
    finally
    {
      updating = false;
    }

    for (FieldHolder fieldHolder : manager)
    {
      fieldHolder.recordInitialValue();
    }

    // Determine the URIs of all the variables actually being used.
    Set<URI> uris = new HashSet<URI>();
    if (performer != null)
    {
      for (VariableTask variable : performer.getUnresolvedVariables())
      {
        uris.add(manager.getURI(variable));
        VariableTask ruleVariable = performer.getRuleVariable(variable);
        if (ruleVariable != null)
        {
          uris.add(manager.getURI(ruleVariable));
        }
      }
    }

    manager.cleanup(uris);

    Composite parent = composite.getParent();
    parent.setRedraw(false);

    List<SetupTaskPerformer> allPerformers = new ArrayList<SetupTaskPerformer>(allPromptedPerfomers);
    if (performer != null)
    {
      allPerformers.add(0, performer);
    }

    // Determine an appropriate field order.
    manager.reorder(allPerformers);

    FieldHolder firstField = null;
    FieldHolder firstEmptyField = null;
    for (FieldHolder fieldHolder : manager)
    {
      if (!fieldHolder.isDisposed())
      {
        if (firstField == null)
        {
          firstField = fieldHolder;
        }

        if (firstEmptyField == null && StringUtil.isEmpty(fieldHolder.getValue()))
        {
          firstEmptyField = fieldHolder;
        }
      }
    }

    if (focusControl != null && !focusControl.isDisposed())
    {
      focusControl.setFocus();
    }
    else
    {
      FieldHolder field = firstEmptyField;
      if (field == null)
      {
        field = firstField;
      }

      if (field != null)
      {
        field.setFocus();
      }
    }

    parent.pack();
    parent.getParent().layout();
    parent.setRedraw(true);

    for (FieldHolder fieldHolder : manager)
    {
      if (!fieldHolder.isDisposed())
      {
        Control control = fieldHolder.getControl();
        PropertyField field = fieldHolder.field;
        Label label = field.getLabel();
        Control helper = field.getHelper();
        for (VariableTask variable : fieldHolder.getVariables())
        {
          String name = variable.getName();
          if (name.startsWith("@<id>"))
          {
            name = name.substring(name.indexOf("name: ") + 6, name.indexOf(')'));
          }

          AccessUtil.setKey(label, name + ".label");
          AccessUtil.setKey(control, name + ".control");
          AccessUtil.setKey(helper, name + ".helper");

          break;
        }
      }
    }

    if (setDefault)
    {
      defaultsSet = true;
    }

    if (!isPageComplete() && (firstEmptyField == null || setDefault))
    {
      // If the page isn't complete but there are no empty fields, then the last change introduced a new field.
      // So we should validate again to be sure there really needs to be more information prompted from the user.
      validate();
    }
  }

  private void validate()
  {
    try
    {
      performer = null;
      String errorMessage = null;

      try
      {
        incompletePerformers.clear();
        allPromptedPerfomers.clear();

        performer = createPerformer(this, fullPrompt);
      }
      catch (OperationCanceledException ex)
      {
        //$FALL-THROUGH$
      }
      catch (IllegalArgumentException ex)
      {
        errorMessage = ex.getMessage();
      }

      setErrorMessage(errorMessage);

      UIUtil.asyncExec(getControl(), new Runnable()
      {
        public void run()
        {
          updateFields();
        }
      });

      if (performer == null)
      {
        setPageComplete(false);
      }
      else
      {
        setPageComplete(true);

        if (!prompted)
        {
          prompted = true;
          gotoNextPage();
        }
      }
    }
    catch (Exception ex)
    {
      SetupUIPlugin.INSTANCE.log(ex);
    }
  }

  private void clearSpecialFieldHolders()
  {
    clearSpecialFieldHolders(INSTALLATION_ID_URI);
    clearSpecialFieldHolders(WORKSPACE_ID_URI);
  }

  private void clearSpecialFieldHolders(URI uri)
  {
    FieldHolderRecord fieldHolderRecord = manager.getFieldHolderRecord(uri);
    if (fieldHolderRecord != null)
    {
      FieldHolder fieldHolder = fieldHolderRecord.getFieldHolder();
      if (fieldHolder != null && !fieldHolder.isDirty())
      {
        fieldHolder.clearValue();
      }
    }
  }

  @Override
  public void enterPage(boolean forward)
  {
    if (forward)
    {
      if (userCallback == null)
      {
        Shell shell = getShell();
        userCallback = new UICallback(shell, shell.getText());
      }

      clearSpecialFieldHolders();
    }

    performer = getWizard().getPerformer();
    if (performer != null && forward)
    {
      performer.setPrompter(this);
      setPageComplete(true);
      gotoNextPage();
    }
    else
    {
      if (!forward)
      {
        getWizard().setSetupContext(originalContext);
        originalContext = null;
      }

      IndexLoader indexLoader = getWizard().getIndexLoader();
      if (indexLoader != null)
      {
        indexLoader.awaitIndexLoad();
      }

      setPageComplete(false);
      validate();
      if (forward && getPreviousPage() == null)
      {
        UIUtil.asyncExec(getControl(), new Runnable()
        {
          public void run()
          {
            if (isPageComplete() && !defaultsSet)
            {
              gotoNextPage();
            }
          }
        });
      }
    }
  }

  @Override
  public void leavePage(boolean forward)
  {
    if (forward)
    {
      originalContext = getWizard().getSetupContext();

      final List<VariableTask> unresolvedVariables = performer.getUnresolvedVariables();
      for (FieldHolder fieldHolder : manager)
      {
        unresolvedVariables.addAll(fieldHolder.getVariables());
      }

      final ResourceSet resourceSet = SetupCoreUtil.createResourceSet();

      User user = getUser();
      final User copiedUser = EcoreUtil.copy(user);
      URI userResourceURI = user.eResource().getURI();
      Resource userResource = resourceSet.createResource(userResourceURI);
      userResource.getContents().add(copiedUser);

      Installation installation = performer.getInstallation();
      Resource installationResource = installation.eResource();
      URI installationResourceURI = installationResource.getURI();
      installationResource
          .setURI(URI.createFileURI(new File(performer.getProductConfigurationLocation(), "org.eclipse.oomph.setup/installation.setup").toString()));

      Workspace workspace = performer.getWorkspace();
      Resource workspaceResource = null;
      URI workspaceResourceURI = null;
      if (workspace != null)
      {
        workspaceResource = workspace.eResource();
        workspaceResourceURI = workspaceResource.getURI();
        workspaceResource
            .setURI(URI.createFileURI(new File(performer.getWorkspaceLocation(), ".metadata/.plugins/org.eclipse.oomph.setup/workspace.setup").toString()));
      }

      Installation copiedInstallation = EcoreUtil.copy(installation);
      URI copiedInstallationResourceURI = installation.eResource().getURI();
      Resource copiedInstallationResource = resourceSet.createResource(copiedInstallationResourceURI);
      copiedInstallationResource.getContents().add(copiedInstallation);

      Workspace copiedWorkspace = EcoreUtil.copy(workspace);
      if (workspace != null)
      {
        URI copiedWorkspaceResourceURI = workspace.eResource().getURI();
        Resource copiedWorkspaceResource = resourceSet.createResource(copiedWorkspaceResourceURI);
        copiedWorkspaceResource.getContents().add(copiedWorkspace);
      }

      performer.recordVariables(copiedInstallation, copiedWorkspace, copiedUser);

      unresolvedVariables.clear();

      getWizard().setSetupContext(SetupContext.create(copiedInstallation, copiedWorkspace, copiedUser));
      setPerformer(performer);

      if (save)
      {
        BaseUtil.saveEObject(copiedUser);

        performer.savePasswords();
      }

      installationResource.setURI(installationResourceURI);
      if (workspaceResource != null)
      {
        workspaceResource.setURI(workspaceResourceURI);
      }
    }
    else
    {
      originalContext = null;

      setPerformer(null);
    }
  }

  public String getValue(VariableTask variable)
  {
    FieldHolder fieldHolder = manager.getFieldHolder(variable, false, true);
    if (fieldHolder != null && (updating || fieldHolder.isDirty()))
    {
      String value = fieldHolder.getValue();
      if (!"".equals(value))
      {
        return value;
      }
    }

    if (updating && performer != null)
    {
      Object value = performer.getMap().get(variable.getName());
      if (value != null)
      {
        return value.toString();
      }
    }

    return null;
  }

  public OS getOS()
  {
    return getWizard().getOS();
  }

  public String getVMPath()
  {
    return getWizard().getVMPath();
  }

  public boolean promptVariables(List<? extends SetupTaskContext> contexts)
  {
    prompted = true;

    @SuppressWarnings("unchecked")
    List<SetupTaskPerformer> performers = (List<SetupTaskPerformer>)contexts;
    allPromptedPerfomers.addAll(performers);
    for (SetupTaskPerformer performer : performers)
    {
      boolean resolvedAll = true;
      List<VariableTask> unresolvedVariables = performer.getUnresolvedVariables();
      for (VariableTask variable : unresolvedVariables)
      {
        FieldHolder fieldHolder = manager.getFieldHolder(variable, false, true);
        if (fieldHolder != null)
        {
          String value = fieldHolder.getValue();
          if (!"".equals(value))
          {
            variable.setValue(value);
          }
          else
          {
            resolvedAll = false;
          }
        }
        else if (unusedVariables.contains(variable.getName()))
        {
          variable.setValue(" ");
        }
        else
        {
          resolvedAll = false;
        }
      }

      if (!resolvedAll)
      {
        incompletePerformers.add(performer);
      }
    }

    boolean isComplete = incompletePerformers.isEmpty();
    return isComplete;
  }

  public UserCallback getUserCallback()
  {
    return userCallback;
  }

  /**
   * @author Ed Merks
   */
  private final class FieldHolder implements ValueListener
  {
    private final Set<VariableTask> variables = new LinkedHashSet<VariableTask>();

    private PropertyField field;

    private String initialValue;

    public FieldHolder(VariableTask variable)
    {
      field = PropertyField.createField(variable);
      field.fill(composite);
      field.addValueListener(this);
      field.getControl().addFocusListener(focusListener);
      variables.add(variable);
    }

    public boolean isDisposed()
    {
      return field == null;
    }

    private Control getControl()
    {
      if (field == null)
      {
        return null;
      }

      Control control = field.getControl();
      Control parent = control.getParent();
      if (parent == composite)
      {
        return control;
      }

      return null;
    }

    public void setFocus()
    {
      if (field == null)
      {
        throw new IllegalStateException("Can't set the value of a disposed field");
      }

      field.setFocus();
    }

    public String getValue()
    {
      return field == null ? initialValue : field.getValue();
    }

    public String getDefaultValue()
    {
      return field == null ? null : field.getDefaultValue();
    }

    public void clearValue()
    {
      initialValue = "";

      if (field != null)
      {
        field.setValue("", false, false);
      }
    }

    public void setValue(String value)
    {
      if (field == null)
      {
        throw new IllegalStateException("Can't set the value of a disposed field");
      }

      initialValue = null;

      field.setValue(value, false);
    }

    public Set<VariableTask> getVariables()
    {
      return Collections.unmodifiableSet(variables);
    }

    public void clear()
    {
      variables.clear();

      if (field instanceof AuthenticatedField)
      {
        AuthenticatedField authenticatedField = (AuthenticatedField)field;
        authenticatedField.clear();
      }
    }

    public void add(VariableTask variable)
    {
      if (variables.add(variable))
      {
        String value = field.getValue();
        if (!"".equals(value))
        {
          variable.setValue(value);
        }
      }
    }

    public void update()
    {
      if (field instanceof AuthenticatedField)
      {
        AuthenticatedField authenticatedField = (AuthenticatedField)field;
        String value = field.getValue();
        Set<Authenticator> allAuthenticators = new LinkedHashSet<Authenticator>();
        for (VariableTask variable : variables)
        {
          if (!StringUtil.isEmpty(value))
          {
            variable.setValue(value);
          }

          Set<? extends Authenticator> authenticators = SetupTaskPerformer.getAuthenticators(variable);
          if (authenticators != null)
          {
            allAuthenticators.addAll(authenticators);
          }
        }

        if (!allAuthenticators.isEmpty())
        {
          for (Iterator<Authenticator> it = allAuthenticators.iterator(); it.hasNext();)
          {
            Authenticator authenticator = it.next();
            if (authenticator.isFiltered())
            {
              it.remove();
            }
          }

          authenticatedField.addAll(allAuthenticators);

          if (allAuthenticators.isEmpty())
          {
            dispose(PreferencesUtil.encrypt(" "));
          }
        }
      }
    }

    public void valueChanged(String oldValue, String newValue) throws Exception
    {
      for (VariableTask variable : variables)
      {
        variable.setValue(newValue);
      }

      clearSpecialFieldHolders();
      validate();
    }

    public void recordInitialValue()
    {
      if (initialValue == null && field != null)
      {
        initialValue = field.getValue();
      }
    }

    public boolean isDirty()
    {
      return field != null && initialValue != null && !initialValue.equals(field.getValue());
    }

    public void dispose(String value)
    {
      if (field != null)
      {
        field.dispose();
        field = null;
      }

      if (StringUtil.isEmpty(initialValue))
      {
        initialValue = value;
      }
    }

    public void dispose()
    {
      if (field != null)
      {
        field.dispose();
        field = null;
      }
    }

    @Override
    public String toString()
    {
      return field == null ? "<disposed>" : field.toString();
    }
  }

  /**
   * @author Ed Merks
   */
  private static class FieldHolderRecord
  {
    private FieldHolder fieldHolder;

    private final Set<URI> variableURIs = new HashSet<URI>();

    public FieldHolderRecord()
    {
    }

    public FieldHolder getFieldHolder()
    {
      return fieldHolder;
    }

    public void setFieldHolder(FieldHolder fieldHolder)
    {
      this.fieldHolder = fieldHolder;
    }

    public Set<URI> getVariableURIs()
    {
      return variableURIs;
    }

    @Override
    public String toString()
    {
      return variableURIs.toString();
    }
  }

  /**
   * @author Ed Merks
   */
  private class FieldHolderManager implements Iterable<FieldHolder>
  {
    private final EList<FieldHolderRecord> fields = new BasicEList<FieldHolderRecord>();

    public Iterator<FieldHolder> iterator()
    {
      final Iterator<FieldHolderRecord> iterator = fields.iterator();
      return new Iterator<FieldHolder>()
      {
        public boolean hasNext()
        {
          return iterator.hasNext();
        }

        public FieldHolder next()
        {
          return iterator.next().getFieldHolder();
        }

        public void remove()
        {
          throw new UnsupportedOperationException();
        }
      };
    }

    public void reorder(List<SetupTaskPerformer> allPerformers)
    {
      List<Control> controls = new ArrayList<Control>();
      Map<FieldHolderRecord, Set<FieldHolderRecord>> ruleUses = new LinkedHashMap<FieldHolderRecord, Set<FieldHolderRecord>>();
      LOOP: for (FieldHolderRecord fieldHolderRecord : fields)
      {
        FieldHolder fieldHolder = fieldHolderRecord.getFieldHolder();
        Control control = fieldHolder.getControl();
        if (control != null)
        {
          controls.add(control);
          for (VariableTask variable : fieldHolder.getVariables())
          {
            for (SetupTaskPerformer performer : allPerformers)
            {
              EAttribute eAttribute = performer.getAttributeRuleVariableData(variable);
              if (eAttribute != null)
              {
                CollectionUtil.addAll(ruleUses, fieldHolderRecord, Collections.<FieldHolderRecord> emptySet());
                continue LOOP;
              }
            }
          }

          // for (VariableTask variable : fieldHolder.getVariables())
          // {
          // for (SetupTaskPerformer performer : allPerformers)
          // {
          // EStructuralFeature.Setting setting = performer.getImpliedVariableData(variable);
          // if (setting != null)
          // {
          // continue LOOP;
          // }
          // }
          // }

          for (VariableTask variable : fieldHolder.getVariables())
          {
            for (SetupTaskPerformer performer : allPerformers)
            {
              VariableTask dependantVariable = performer.getRuleVariableData(variable);
              if (dependantVariable != null)
              {
                VariableTask ruleVariable = performer.getRuleVariable(dependantVariable);
                if (ruleVariable != null)
                {
                  FieldHolderRecord dependantFieldHolderRecord = getFieldHolderRecord(getURI(ruleVariable));
                  if (dependantFieldHolderRecord != null)
                  {
                    CollectionUtil.add(ruleUses, dependantFieldHolderRecord, fieldHolderRecord);
                    continue LOOP;
                  }
                }
              }
            }
          }
        }
      }

      int fieldsSize = fields.size();
      if (fieldsSize > 1)
      {
        int index = 0;
        int maxPosition = fieldsSize - 1;
        for (Map.Entry<FieldHolderRecord, Set<FieldHolderRecord>> entry : ruleUses.entrySet())
        {
          FieldHolderRecord fieldHolderRecord = entry.getKey();
          fields.move(index++, fieldHolderRecord);
          Set<FieldHolderRecord> fieldHolderRecords = entry.getValue();
          for (FieldHolderRecord dependantFieldHolderRecord : fieldHolderRecords)
          {
            fields.move(Math.min(index++, maxPosition), dependantFieldHolderRecord);
          }
        }
      }

      int size = controls.size();
      if (size > 1)
      {
        List<Control> children = Arrays.asList(composite.getChildren());

        int controlOffset = 0;
        for (Control child : children)
        {
          if (controls.contains(child))
          {
            break;
          }

          ++controlOffset;
        }

        Control target = children.get(PropertyField.NUM_COLUMNS - 1);
        int count = 0;
        for (FieldHolder fieldHolder : this)
        {
          Control control = fieldHolder.getControl();
          if (control != null)
          {
            int index = children.indexOf(control) - controlOffset;
            Control newTarget = null;
            for (int j = PropertyField.NUM_COLUMNS - 1; j >= 0; --j)
            {
              Control child = children.get(index + j);
              if (newTarget == null)
              {
                newTarget = child;
                if (index == count)
                {
                  break;
                }
              }

              child.moveBelow(target);
            }

            target = newTarget;
            count += PropertyField.NUM_COLUMNS;
          }
        }
      }
    }

    public void cleanup(Set<URI> uris)
    {
      LOOP: for (FieldHolderRecord fieldHolderRecord : fields)
      {
        for (URI uri : fieldHolderRecord.getVariableURIs())
        {
          if (uris.contains(uri))
          {
            continue LOOP;
          }
        }

        FieldHolder fieldHolder = fieldHolderRecord.getFieldHolder();
        if (fieldHolder.getVariables().isEmpty() && !fieldHolder.isDirty())
        {
          fieldHolder.dispose();
        }
      }
    }

    public URI getURI(VariableTask variable)
    {
      String name = variable.getName();
      if (variable.getAnnotation(AnnotationConstants.ANNOTATION_GLOBAL_VARIABLE) != null)
      {
        return URI.createURI("#" + name);
      }

      Resource resource = variable.eResource();
      URI uri;
      if (resource == null)
      {
        uri = URI.createURI("#");
      }
      else
      {
        EObject eObject = resource.getContents().get(0);
        if (eObject instanceof Installation || eObject instanceof Workspace)
        {
          uri = URI.createURI(resource.getURI().lastSegment()).appendFragment(resource.getURIFragment(variable));
        }
        else
        {
          uri = EcoreUtil.getURI(variable);
        }
      }

      uri = uri.appendFragment(uri.fragment() + "~" + name);
      return uri;
    }

    private FieldHolderRecord getFieldHolderRecord(URI uri)
    {
      for (FieldHolderRecord fieldHolderRecord : fields)
      {
        if (fieldHolderRecord.getVariableURIs().contains(uri))
        {
          return fieldHolderRecord;
        }
      }

      return null;
    }

    public void associate(VariableTask variable, FieldHolder fieldHolder)
    {
      URI uri = getURI(variable);
      for (FieldHolderRecord fieldHolderRecord : fields)
      {
        if (fieldHolderRecord.getFieldHolder() == fieldHolder)
        {
          fieldHolderRecord.getVariableURIs().add(uri);
          break;
        }
      }
    }

    public FieldHolder getFieldHolder(VariableTask variable, boolean demandCreate, boolean includeDisposed)
    {
      URI uri = getURI(variable);
      FieldHolderRecord fieldHolderRecord = getFieldHolderRecord(uri);
      FieldHolder fieldHolder = null;
      if (fieldHolderRecord == null)
      {
        if (!demandCreate)
        {
          return null;
        }

        fieldHolderRecord = new FieldHolderRecord();
        fieldHolderRecord.getVariableURIs().add(uri);
        fields.add(fieldHolderRecord);
      }
      else
      {
        fieldHolder = fieldHolderRecord.getFieldHolder();
        if (fieldHolder.isDisposed())
        {
          if (includeDisposed)
          {
            return fieldHolder;
          }

          if (!demandCreate)
          {
            return null;
          }

          fieldHolder = null;
        }
      }

      if (fieldHolder == null)
      {
        fieldHolder = new FieldHolder(variable);
        fieldHolderRecord.setFieldHolder(fieldHolder);
      }
      else if (!updating)
      {
        fieldHolder.add(variable);
      }

      return fieldHolder;
    }

    @Override
    public String toString()
    {
      return fields.toString();
    }
  }

}
