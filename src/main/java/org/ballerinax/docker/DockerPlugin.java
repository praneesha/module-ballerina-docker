/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinax.docker;

import org.ballerinalang.compiler.plugins.AbstractCompilerPlugin;
import org.ballerinalang.compiler.plugins.SupportedAnnotationPackages;
import org.ballerinalang.model.tree.AnnotationAttachmentNode;
import org.ballerinalang.model.tree.EndpointNode;
import org.ballerinalang.model.tree.PackageNode;
import org.ballerinalang.model.tree.ServiceNode;
import org.ballerinalang.util.diagnostic.Diagnostic;
import org.ballerinalang.util.diagnostic.DiagnosticLog;
import org.ballerinax.docker.exceptions.DockerPluginException;
import org.ballerinax.docker.models.DockerDataHolder;
import org.ballerinax.docker.utils.DockerGenUtils;
import org.wso2.ballerinalang.compiler.tree.BLangEndpoint;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangRecordLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangRecordLiteral.BLangRecordKeyValue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.ballerinax.docker.utils.DockerGenUtils.printError;

/**
 * Compiler plugin to generate docker artifacts.
 */
@SupportedAnnotationPackages(
        value = "ballerinax.docker"
)
public class DockerPlugin extends AbstractCompilerPlugin {
    private static boolean canProcess;
    private DockerAnnotationProcessor dockerAnnotationProcessor;
    private DiagnosticLog dlog;

    private static synchronized void setCanProcess(boolean val) {
        canProcess = val;
    }

    @Override
    public void init(DiagnosticLog diagnosticLog) {
        this.dlog = diagnosticLog;
        dockerAnnotationProcessor = new DockerAnnotationProcessor();
        setCanProcess(false);
    }

    @Override
    public void process(PackageNode packageNode) {
        // extract port values from services.
        List<? extends EndpointNode> endpointNodes = packageNode.getGlobalEndpoints();
        for (EndpointNode endpointNode : endpointNodes) {
            List<BLangRecordKeyValue> keyValues = ((BLangRecordLiteral)
                    ((BLangEndpoint) endpointNode).configurationExpr).getKeyValuePairs();
            keyValues.forEach(keyValue -> {
                if ("port".equals(keyValue.getKey().toString())) {
                    DockerDataHolder.getInstance().addPort(Integer.parseInt(keyValue.getValue().toString()));
                }
            });
        }
    }

    @Override
    public void process(ServiceNode serviceNode, List<AnnotationAttachmentNode> annotations) {
        setCanProcess(true);
        try {
            processDockerAnnotation(annotations);
            List<BLangRecordLiteral.BLangRecordKeyValue> endpointConfig =
                    ((BLangRecordLiteral) serviceNode.getAnonymousEndpointBind()).getKeyValuePairs();
            DockerDataHolder.getInstance().addPort(extractPort(endpointConfig));
        } catch (DockerPluginException e) {
            dlog.logDiagnostic(Diagnostic.Kind.ERROR, serviceNode.getPosition(), e.getMessage());
        }
    }

    @Override
    public void process(EndpointNode endpointNode, List<AnnotationAttachmentNode> annotations) {
        setCanProcess(true);
        try {
            processDockerAnnotation(annotations);
        } catch (DockerPluginException e) {
            dlog.logDiagnostic(Diagnostic.Kind.ERROR, endpointNode.getPosition(), e.getMessage());
        }
    }

    @Override
    public void codeGenerated(Path binaryPath) {
        if (canProcess) {
            String filePath = binaryPath.toAbsolutePath().toString();
            String userDir = new File(filePath).getParentFile().getAbsolutePath();
            String targetPath = userDir + File.separator + "docker" + File.separator;
            DockerAnnotationProcessor dockerAnnotationProcessor = new DockerAnnotationProcessor();
            try {
                DockerGenUtils.deleteDirectory(targetPath);
                dockerAnnotationProcessor.processDockerModel(DockerDataHolder.getInstance(), filePath, targetPath);
            } catch (DockerPluginException e) {
                printError(e.getMessage());
                dlog.logDiagnostic(Diagnostic.Kind.ERROR, null, e.getMessage());
                try {
                    DockerGenUtils.deleteDirectory(targetPath);
                } catch (DockerPluginException ignored) {
                }
            }
        }
    }

    /**
     * Process annotations and create model objects.
     *
     * @param annotations annotation attachment node list.
     * @throws DockerPluginException if an error occurs while creating model objects
     */
    private void processDockerAnnotation(List<AnnotationAttachmentNode> annotations) throws DockerPluginException {
        for (AnnotationAttachmentNode attachmentNode : annotations) {
            String annotationKey = attachmentNode.getAnnotationName().getValue();
            switch (annotationKey) {
                case "Config":
                    DockerDataHolder.getInstance().setDockerModel(
                            dockerAnnotationProcessor.processConfigAnnotation(attachmentNode));
                    break;
                case "CopyFiles":
                    DockerDataHolder.getInstance().addExternalFile(
                            dockerAnnotationProcessor.processCopyFileAnnotation(attachmentNode));
                    break;
                default:
                    break;
            }
        }
    }

    private int extractPort(List<BLangRecordLiteral.BLangRecordKeyValue> endpointConfig) throws
            DockerPluginException {
        for (BLangRecordLiteral.BLangRecordKeyValue keyValue : endpointConfig) {
            String key = keyValue.getKey().toString();
            if ("port".equals(key)) {
                return Integer.parseInt(keyValue.getValue().toString());
            }
        }
        throw new DockerPluginException("Unable to extract port from anonymous endpoint");
    }
}
