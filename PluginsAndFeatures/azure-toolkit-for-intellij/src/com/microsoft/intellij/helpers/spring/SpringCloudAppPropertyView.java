/*
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.intellij.helpers.spring;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.Comparing;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.microsoft.azure.credentials.AzureCliCredentials;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.DeploymentInstance;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.DeploymentResourceProperties;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.DeploymentSettings;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.implementation.AppPlatformManager;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.implementation.AppResourceInner;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.implementation.DeploymentResourceInner;
import com.microsoft.intellij.helpers.base.BaseEditor;
import com.microsoft.intellij.util.PluginUtil;
import com.microsoft.rest.LogLevel;
import com.microsoft.tooling.msservices.components.DefaultLoader;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import rx.Observable;
import rx.schedulers.Schedulers;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.Dimension;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class SpringCloudAppPropertyView extends BaseEditor {
    private JPanel pnlOverview;
    private JLabel lblAppName;
    private JLabel lblCPU;
    private JLabel lblStatus;
    private JLabel lblMemory;
    private HyperlinkLabel lblURL;
    private JLabel lblAppInstanceCount;
    private JLabel lblCreateTime;
    private JPanel pnlJVMArguments;
    private JTextField txtJVMOptions;
    private JPanel pnlInstancesHolder;
    private JPanel pnlEnvironmentVariables;
    private JPanel pnlRoot;
    private JButton btnDiscard;
    private JButton btnSave;
    private JButton btnRefresh;
    private JPanel pnlEnvironmentHolder;
    private JPanel pnlInstances;
    private JBTable tblEnvironmentVariables;
    private DefaultTableModel environmentVariablesTableModel;
    private JBTable tblInstances;
    private DefaultTableModel instancesTableModel;

    private final Map<String, String> editedEnvironmentVariable;

    private String subscription;
    private String resourceGroup;
    private String cluster;
    private String appName;

    private AppPlatformManager appPlatformManager;
    private AppResourceInner appResourceInner;
    private DeploymentResourceInner deploymentResourceInner;

    public SpringCloudAppPropertyView(String subscription, String resourceGroup, String cluster, String appName) {
        editedEnvironmentVariable = new LinkedHashMap<>();

        this.subscription = subscription;
        this.resourceGroup = resourceGroup;
        this.cluster = cluster;
        this.appName = appName;

        btnSave.addActionListener(e -> save());
        btnDiscard.addActionListener(e -> discard());
        btnRefresh.addActionListener(e -> onLoadSpringCloudApp(subscription, resourceGroup, cluster, appName));
        txtJVMOptions.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent documentEvent) {
                syncButtonStatus();
            }
        });

        onLoadSpringCloudApp(subscription, resourceGroup, cluster, appName);
    }

    @NotNull
    @Override
    public JComponent getComponent() {
        return pnlRoot;
    }

    @NotNull
    @Override
    public String getName() {
        return appName;
    }

    @Override
    public void dispose() {

    }

    private void createUIComponents() {
        instancesTableModel = new DefaultTableModel() {
            public boolean isCellEditable(int var1, int var2) {
                return false;
            }
        };
        instancesTableModel.addColumn("App Instances Name");
        instancesTableModel.addColumn("Status");
        instancesTableModel.addColumn("Discover Status");

        tblInstances = new JBTable(instancesTableModel);
        tblInstances.setRowSelectionAllowed(true);
        tblInstances.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblInstances.getEmptyText().setText("Loading instances status");
        tblInstances.setPreferredSize(new Dimension(-1, 200));

        final ToolbarDecorator instanceDecorator = ToolbarDecorator.createDecorator(tblInstances);
        pnlInstances = instanceDecorator.createPanel();

        // TODO: place custom component creation code here
        environmentVariablesTableModel = new DefaultTableModel();
        environmentVariablesTableModel.addColumn("Key");
        environmentVariablesTableModel.addColumn("Value");

        tblEnvironmentVariables = new JBTable(environmentVariablesTableModel);
        tblEnvironmentVariables.setRowSelectionAllowed(true);
        tblEnvironmentVariables.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblEnvironmentVariables.getEmptyText().setText("Loading environment variables");

        tblEnvironmentVariables.addPropertyChangeListener(evt -> {
            if ("tableCellEditor".equals(evt.getPropertyName())) {
                if (!tblEnvironmentVariables.isEditing()) {
                    editedEnvironmentVariable.clear();
                    int row = 0;
                    while (row < environmentVariablesTableModel.getRowCount()) {
                        final Object keyObj = environmentVariablesTableModel.getValueAt(row, 0);
                        String key = "";
                        String value = "";
                        if (keyObj != null) {
                            key = (String) keyObj;
                        }
                        if (key.isEmpty() || editedEnvironmentVariable.containsKey(key)) {
                            environmentVariablesTableModel.removeRow(row);
                            continue;
                        }
                        final Object valueObj = environmentVariablesTableModel.getValueAt(row, 1);
                        if (valueObj != null) {
                            value = (String) valueObj;
                        }
                        editedEnvironmentVariable.put(key, value);
                        ++row;
                    }
                }
                syncButtonStatus();
            }
        });

        final AnActionButton btnAdd = new AnActionButton("Add", AllIcons.General.Add) {
            @Override
            public void actionPerformed(AnActionEvent anActionEvent) {
                if (tblEnvironmentVariables.isEditing()) {
                    tblEnvironmentVariables.getCellEditor().stopCellEditing();
                }
                environmentVariablesTableModel.addRow(new String[]{"", ""});
                tblEnvironmentVariables.editCellAt(tblEnvironmentVariables.getRowCount() - 1, 0);
            }
        };

        final AnActionButton btnRemove = new AnActionButton("Remove", AllIcons.General.Remove) {
            @Override
            public void actionPerformed(AnActionEvent anActionEvent) {
                final int selectedRow = tblEnvironmentVariables.getSelectedRow();
                if (selectedRow == -1) {
                    return;
                }
                editedEnvironmentVariable.remove(environmentVariablesTableModel.getValueAt(selectedRow, 0));
                environmentVariablesTableModel.removeRow(selectedRow);
            }
        };

        final AnActionButton btnEdit = new AnActionButton("Edit", AllIcons.Actions.Edit) {
            @Override
            public void actionPerformed(AnActionEvent anActionEvent) {
                final int selectedRow = tblEnvironmentVariables.getSelectedRow();
                final int selectedCol = tblEnvironmentVariables.getSelectedColumn();
                if (selectedRow == -1 || selectedCol == -1) {
                    return;
                }
                tblEnvironmentVariables.editCellAt(selectedRow, selectedCol);
            }
        };

        final ToolbarDecorator tableToolbarDecorator = ToolbarDecorator.createDecorator(tblEnvironmentVariables)
                .addExtraActions(btnAdd, btnRemove, btnEdit).setToolbarPosition(ActionToolbarPosition.RIGHT);
        pnlEnvironmentVariables = tableToolbarDecorator.createPanel();
    }

    private void onLoadSpringCloudApp(String subscription, String resourceGroup, String cluster, String appName) {
        lblStatus.setText("Loading...");
        lblMemory.setText("Loading...");
        lblCPU.setText("Loading...");
        lblAppInstanceCount.setText("Loading...");
        lblURL.setText("Loading...");
        lblCreateTime.setText("Loading...");
        lblAppName.setText("Loading...");

        tblInstances.getEmptyText().setText("Loading...");
        instancesTableModel.getDataVector().removeAllElements();
        tblInstances.updateUI();
        tblEnvironmentVariables.getEmptyText().setText("Loading...");
        environmentVariablesTableModel.getDataVector().removeAllElements();
        tblEnvironmentVariables.updateUI();

        txtJVMOptions.setEditable(false);
        txtJVMOptions.setText("Loading...");

        Observable.fromCallable(() -> getAppPlatformManager().apps().inner().get(resourceGroup, cluster, appName))
                .subscribeOn(Schedulers.newThread())
                .subscribe(cloudApp -> DefaultLoader.getIdeHelper().invokeLater(() -> {
                    fillSpringCloudApp(cloudApp, resourceGroup, cluster, appName);
                }));
    }

    private void fillSpringCloudApp(AppResourceInner appResourceInner, String resourceGroup, String cluster, String appName) {
        Observable.fromCallable(() -> getAppPlatformManager().deployments().inner().get(resourceGroup, cluster, appName, appResourceInner.properties().activeDeploymentName()))
                .subscribeOn(Schedulers.newThread())
                .subscribe(activeDeployment -> DefaultLoader.getIdeHelper().invokeLater(() -> {
                    fillSpringCloudDeployment(activeDeployment);
                }));
        this.appResourceInner = appResourceInner;
        lblAppName.setText(appName);
        lblCreateTime.setText(appResourceInner.properties().createdTime().toString());
        if (StringUtils.isEmpty(appResourceInner.properties().url())) {
            lblURL.setText("N/A");
        } else {
            lblURL.setHyperlinkText(appResourceInner.properties().url());
            lblURL.setHyperlinkTarget(appResourceInner.properties().url());
        }
    }

    private void fillSpringCloudDeployment(DeploymentResourceInner deploymentResourceInner) {
        this.deploymentResourceInner = deploymentResourceInner;
        lblStatus.setText(deploymentResourceInner.properties().status().toString());

        final DeploymentSettings deploymentSettings = deploymentResourceInner.properties().deploymentSettings();
        lblAppInstanceCount.setText(String.valueOf(deploymentSettings.instanceCount()));
        lblCPU.setText(String.valueOf(deploymentSettings.cpu()));
        lblMemory.setText(String.valueOf(deploymentSettings.memoryInGB()));
        txtJVMOptions.setEditable(true);
        txtJVMOptions.setText(deploymentSettings.jvmOptions());

        fillEnvironmentVariables(deploymentSettings.environmentVariables());

        tblInstances.getEmptyText().setText("Empty");
        if (CollectionUtils.isNotEmpty(deploymentResourceInner.properties().instances())) {
            instancesTableModel.getDataVector().removeAllElements();
            for (final DeploymentInstance deploymentInstance : deploymentResourceInner.properties().instances()) {
                instancesTableModel.addRow(new String[]{deploymentInstance.name(), deploymentInstance.status(), deploymentInstance.discoveryStatus()});
            }
        }

        syncButtonStatus();
        pnlEnvironmentVariables.revalidate();
    }

    private void fillEnvironmentVariables(Map<String, String> environmentVariables) {
        tblEnvironmentVariables.getEmptyText().setText("Empty");
        if (MapUtils.isNotEmpty(environmentVariables)) {
            editedEnvironmentVariable.clear();
            editedEnvironmentVariable.putAll(environmentVariables);

            environmentVariablesTableModel.getDataVector().removeAllElements();
            for (final String key : environmentVariables.keySet()) {
                environmentVariablesTableModel.addRow(new String[]{key, environmentVariables.get(key)});
            }
        }
    }

    public synchronized AppPlatformManager getAppPlatformManager() throws IOException {
        if (appPlatformManager == null) {
            appPlatformManager = AppPlatformManager.configure()
                    .withLogLevel(LogLevel.BASIC)
                    .withUserAgent("test")
                    .authenticate(AzureCliCredentials.create(), subscription);
        }
        return appPlatformManager;
    }

    private void discard() {
        fillEnvironmentVariables(deploymentResourceInner.properties().deploymentSettings().environmentVariables());
        txtJVMOptions.setText(deploymentResourceInner.properties().deploymentSettings().jvmOptions());
        syncButtonStatus();
    }

    private void save() {
        if (isEnvironmentVariableModified() || isJavaOptsModified()) {
            ProgressManager.getInstance().run(new Task.Modal(null, "Updating spring cloud app", true) {
                @Override
                public void run(@NotNull ProgressIndicator progressIndicator) {
                    try {
                        progressIndicator.setIndeterminate(true);
                        DeploymentResourceProperties deploymentResourceProperties = deploymentResourceInner.properties();
                        final DeploymentSettings deploymentSettings = deploymentResourceProperties.deploymentSettings()
                                .withEnvironmentVariables(editedEnvironmentVariable).withJvmOptions(txtJVMOptions.getText());
                        deploymentResourceProperties = deploymentResourceProperties.withDeploymentSettings(deploymentSettings);
                        deploymentResourceInner = getAppPlatformManager().deployments().inner().update(resourceGroup, cluster, appName, appResourceInner.properties().activeDeploymentName(), deploymentResourceProperties);
                        syncButtonStatus();
                        ApplicationManager.getApplication().invokeLater(() -> PluginUtil.displayInfoDialog("Update successfully", "Update app configuration successfully"));
                    } catch (IOException e) {
                        ApplicationManager.getApplication().invokeLater(() -> PluginUtil.displayErrorDialog("Failed to update app configuration", e.getMessage()));
                    }
                }
            });
        }
    }

    private void syncButtonStatus() {
        final boolean isConfigurationModified = isJavaOptsModified() || isEnvironmentVariableModified();
        btnSave.setEnabled(isConfigurationModified);
        btnDiscard.setEnabled(isConfigurationModified);
    }

    private boolean isEnvironmentVariableModified() {
        if (deploymentResourceInner == null) {
            return false;
        }
        final Map oldEnvironmentVariable = deploymentResourceInner.properties().deploymentSettings().environmentVariables();
        return !Comparing.equal(oldEnvironmentVariable, editedEnvironmentVariable);
    }

    private boolean isJavaOptsModified() {
        if (deploymentResourceInner == null) {
            return false;
        }
        final String oldJavaOpts = deploymentResourceInner.properties().deploymentSettings().jvmOptions();
        return !StringUtils.equals(oldJavaOpts, txtJVMOptions.getText());
    }
}
