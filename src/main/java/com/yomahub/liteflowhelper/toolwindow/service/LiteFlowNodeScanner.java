package com.yomahub.liteflowhelper.toolwindow.service;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.yomahub.liteflowhelper.toolwindow.model.LiteFlowNodeInfo;
import com.yomahub.liteflowhelper.toolwindow.model.NodeType;
import com.yomahub.liteflowhelper.utils.LiteFlowXmlUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 负责扫描项目中的 LiteFlow 节点定义。
 * 包括基于Java类的继承式节点、XML中定义的脚本节点以及声明式注解节点。
 * [重构] 此类现在将具体的判断逻辑委托给 LiteFlowXmlUtil。
 */
public class LiteFlowNodeScanner {

    private static final Logger LOG = Logger.getInstance(LiteFlowNodeScanner.class);

    // LiteFlow核心节点组件的基类，用于查找它们的子类 (继承式)
    private static final String[] LITEFLOW_BASE_CLASSES = {
            "com.yomahub.liteflow.core.NodeSwitchComponent",
            "com.yomahub.liteflow.core.NodeBooleanComponent",
            "com.yomahub.liteflow.core.NodeForComponent",
            "com.yomahub.liteflow.core.NodeIteratorComponent",
            LiteFlowXmlUtil.NODE_COMPONENT_CLASS
    };

    /**
     * 查找项目中的所有LiteFlow节点。
     * @param project 当前项目
     * @return LiteFlowNodeInfo 列表，如果项目处于Dumb Mode则返回空列表。
     */
    public List<LiteFlowNodeInfo> findLiteFlowNodes(@NotNull Project project) {
        return ScannerUtil.runInReadAction(project, "LiteFlow 节点", () -> {
            List<LiteFlowNodeInfo> nodeInfos = new ArrayList<>();

            // 扫描继承式组件
            List<LiteFlowNodeInfo> inheritanceNodes = findJavaClassInheritanceNodes(project);
            nodeInfos.addAll(inheritanceNodes);
            LOG.info("继承式组件: " + inheritanceNodes.size() + " 个");

            // 扫描XML脚本组件
            List<LiteFlowNodeInfo> xmlNodes = findXmlScriptNodes(project);
            nodeInfos.addAll(xmlNodes);
            LOG.info("XML脚本组件: " + xmlNodes.size() + " 个");

            // 扫描声明式组件 (合并了类和方法扫描，提高效率)
            List<LiteFlowNodeInfo> declarativeNodes = findDeclarativeNodes(project);
            nodeInfos.addAll(declarativeNodes);
            LOG.info("声明式组件: " + declarativeNodes.size() + " 个");

            nodeInfos.sort(Comparator.comparing(LiteFlowNodeInfo::getNodeId));
            return nodeInfos;
        });
    }

    /**
     * 查找项目中所有基于Java类继承定义的LiteFlow节点。
     */
    private List<LiteFlowNodeInfo> findJavaClassInheritanceNodes(@NotNull Project project) {
        List<LiteFlowNodeInfo> javaNodeInfos = new ArrayList<>();
        JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);

        // [优化] 预先获取 NodeComponent 基类，避免在循环中重复查找
        PsiClass nodeComponentBaseClass = psiFacade.findClass(LiteFlowXmlUtil.NODE_COMPONENT_CLASS, allScope);

        // [优化] 调整为debug级别
        LOG.debug("开始扫描继承式组件，基类列表: " + String.join(", ", LITEFLOW_BASE_CLASSES));

        for (String baseClassName : LITEFLOW_BASE_CLASSES) {
            if (project.isDisposed()) {
                return Collections.emptyList();
            }

            PsiClass baseClass = psiFacade.findClass(baseClassName, allScope);
            if (baseClass == null) {
                LOG.warn("未找到基类: " + baseClassName + "，可能是 LiteFlow 依赖未正确加载");
                continue;
            }

            LOG.debug("正在查找 " + baseClassName + " 的子类...");
            Collection<PsiClass> inheritors = ClassInheritorsSearch.search(baseClass, projectScope, true).findAll();
            LOG.debug("找到 " + inheritors.size() + " 个 " + baseClassName + " 的子类");

            for (PsiClass inheritor : inheritors) {
                if (project.isDisposed()) {
                    return Collections.emptyList();
                }

                LOG.debug("检查类: " + (inheritor.getQualifiedName() != null ? inheritor.getQualifiedName() : inheritor.getName()));

                // [重构] 使用优化后的工具类方法，传入预获取的基类
                if (LiteFlowXmlUtil.isInheritanceComponent(inheritor, nodeComponentBaseClass)) {
                    String nodeIdFromAnnotation = LiteFlowXmlUtil.getNodeIdFromComponentAnnotations(inheritor);
                    String nodeName = inheritor.getName();
                    String primaryId = nodeIdFromAnnotation != null ? nodeIdFromAnnotation : LiteFlowXmlUtil.convertClassNameToCamelCase(nodeName);

                    // [优化] 使用 StringUtil 统一空值检查
                    if (StringUtil.isEmpty(primaryId)) {
                        LOG.warn("跳过继承式组件，无法确定Node ID: " + inheritor.getQualifiedName());
                        continue;
                    }

                    NodeType nodeType = determineNodeTypeFromSuperClass(baseClassName);

                    boolean flag = javaNodeInfos.stream().anyMatch(liteFlowNodeInfo -> liteFlowNodeInfo.getNodeName().equals(nodeName));
                    if (!flag) {
                        javaNodeInfos.add(new LiteFlowNodeInfo(primaryId, nodeName, nodeType, inheritor, "继承式"));
                        // [优化] 调整为debug级别
                        LOG.debug("找到继承式组件: " + primaryId + " (" + nodeName + ")");
                    } else {
                        LOG.debug("跳过重复组件: " + nodeName);
                    }
                } else {
                    LOG.debug("类 " + inheritor.getName() + " 不符合继承式组件条件");
                }
            }
        }

        LOG.debug("继承式组件扫描完成，共找到 " + javaNodeInfos.size() + " 个组件");
        return javaNodeInfos;
    }

    /**
     * [重构] 提取公共方法：查找所有带有组件注解的候选类
     */
    private Set<PsiClass> findCandidateComponentClasses(@NotNull Project project) {
        JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);

        Set<PsiClass> candidates = new HashSet<>();

        // 查找 @LiteflowComponent 注解的类
        PsiClass liteflowAnnotation = javaPsiFacade.findClass(LiteFlowXmlUtil.LITEFLOW_COMPONENT_ANNOTATION, allScope);
        if (liteflowAnnotation != null) {
            Collection<PsiClass> lfComponents = AnnotatedElementsSearch.searchPsiClasses(liteflowAnnotation, projectScope).findAll();
            candidates.addAll(lfComponents);
            LOG.debug("找到 " + lfComponents.size() + " 个 @LiteflowComponent 标注的类");
        } else {
            LOG.warn("未找到 @LiteflowComponent 注解类，可能是 LiteFlow 依赖未正确加载");
        }

        // 查找 @Component 注解的类
        PsiClass springAnnotation = javaPsiFacade.findClass(LiteFlowXmlUtil.SPRING_COMPONENT_ANNOTATION, allScope);
        if (springAnnotation != null) {
            Collection<PsiClass> springComponents = AnnotatedElementsSearch.searchPsiClasses(springAnnotation, projectScope).findAll();
            candidates.addAll(springComponents);
            LOG.debug("找到 " + springComponents.size() + " 个 @Component 标注的类");
        } else {
            LOG.warn("未找到 @Component 注解类");
        }

        return candidates;
    }

    /**
     * [重构] 扫描声明式组件 (合并类组件和方法组件扫描)
     */
    private List<LiteFlowNodeInfo> findDeclarativeNodes(@NotNull Project project) {
        List<LiteFlowNodeInfo> nodeInfos = new ArrayList<>();
        LOG.info("开始扫描声明式组件...");

        // 1. 获取所有候选类 (带有 @LiteflowComponent 或 @Component 的类)
        Set<PsiClass> candidateClasses = findCandidateComponentClasses(project);

        if (candidateClasses.isEmpty()) {
            LOG.warn("未找到任何带组件注解的类");
            return Collections.emptyList();
        }

        LOG.debug("共找到 " + candidateClasses.size() + " 个候选组件类，开始筛选...");

        // [优化] 预先获取 NodeComponent 基类，用于在循环中快速排除继承式组件
        PsiClass nodeComponentBaseClass = JavaPsiFacade.getInstance(project).findClass(LiteFlowXmlUtil.NODE_COMPONENT_CLASS, GlobalSearchScope.allScope(project));

        for (PsiClass psiClass : candidateClasses) {
            if (project.isDisposed()) {
                return Collections.emptyList();
            }

            // 检查是否为具体的类
            if (psiClass.isInterface() || psiClass.isAnnotationType() || psiClass.isEnum() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                continue;
            }

            // 排除继承式组件 (避免重复)
            if (LiteFlowXmlUtil.isInheritanceComponent(psiClass, nodeComponentBaseClass)) {
                continue;
            }

            boolean isClassDeclarative = false;

            // 2. 检查是否为 "类声明式组件"
            if (LiteFlowXmlUtil.isClassDeclarativeComponent(psiClass)) {
                String nodeId = LiteFlowXmlUtil.getNodeIdFromComponentAnnotations(psiClass);
                if (nodeId == null) {
                    nodeId = LiteFlowXmlUtil.convertClassNameToCamelCase(psiClass.getName());
                }

                if (!StringUtil.isEmpty(nodeId)) {
                    // 确定 NodeType
                    String nodeTypeStr = "COMMON";
                    for(PsiMethod method : psiClass.getMethods()){
                        PsiAnnotation annotation = method.getAnnotation(LiteFlowXmlUtil.LITEFLOW_METHOD_ANNOTATION);
                        if(annotation != null){
                            nodeTypeStr = LiteFlowXmlUtil.getAnnotationEnumValue(annotation, "nodeType", LiteFlowXmlUtil.LITEFLOW_NODE_TYPE_ENUM_FQ);
                            if(nodeTypeStr == null) {
                                nodeTypeStr = "COMMON";
                            }
                            break;
                        }
                    }
                    NodeType nodeType = NodeType.fromDeclarativeNodeType(nodeTypeStr);
                    String nodeName = psiClass.getName();
                    nodeInfos.add(new LiteFlowNodeInfo(nodeId, nodeName, nodeType, psiClass, "类声明式"));
                    isClassDeclarative = true; // 标记为已作为类声明式组件添加
                }
            }

            // 3. 检查是否包含 "方法声明式组件" (如果已经识别为类声明式，通常不会再包含方法声明式，但为了完整性可以检查)
            // LiteFlow 规则：一个类如果已经是类声明式组件，其内部的方法通常共同构成这个组件的逻辑。
            // 但如果类本身只是一个 Bean 容器 (@Component)，内部可能有多个 @LiteflowMethod 定义不同的组件。
            if (!isClassDeclarative) {
                for (PsiMethod method : psiClass.getMethods()) {
                    if (LiteFlowXmlUtil.isMethodDeclarativeComponent(method)) {
                        PsiAnnotation lfMethodAnnotation = method.getAnnotation(LiteFlowXmlUtil.LITEFLOW_METHOD_ANNOTATION);
                        String nodeId = LiteFlowXmlUtil.getAnnotationAttributeValue(lfMethodAnnotation, "nodeId");
                        String nodeName = LiteFlowXmlUtil.getAnnotationAttributeValue(lfMethodAnnotation, "nodeName");
                        String nodeTypeStr = LiteFlowXmlUtil.getAnnotationEnumValue(lfMethodAnnotation, "nodeType", LiteFlowXmlUtil.LITEFLOW_NODE_TYPE_ENUM_FQ);
                        if (nodeTypeStr == null) {
                            nodeTypeStr = "COMMON";
                        }

                        NodeType nodeType = NodeType.fromDeclarativeNodeType(nodeTypeStr);
                        nodeInfos.add(new LiteFlowNodeInfo(nodeId, nodeName, nodeType, method, "方法声明式"));
                    }
                }
            }
        }

        LOG.debug("声明式组件扫描完成，共找到 " + nodeInfos.size() + " 个组件");
        return nodeInfos;
    }


    /**
     * 根据Java类直接继承的LiteFlow基类全限定名来确定节点类型 (用于继承式组件)。
     */
    private NodeType determineNodeTypeFromSuperClass(String directSuperClassName) {
        if ("com.yomahub.liteflow.core.NodeComponent".equals(directSuperClassName)) {
            return NodeType.COMMON_COMPONENT;
        }
        if ("com.yomahub.liteflow.core.NodeSwitchComponent".equals(directSuperClassName)) {
            return NodeType.SWITCH_COMPONENT;
        }
        if ("com.yomahub.liteflow.core.NodeBooleanComponent".equals(directSuperClassName)) {
            return NodeType.BOOLEAN_COMPONENT;
        }
        if ("com.yomahub.liteflow.core.NodeForComponent".equals(directSuperClassName)) {
            return NodeType.FOR_COMPONENT;
        }
        if ("com.yomahub.liteflow.core.NodeIteratorComponent".equals(directSuperClassName)) {
            return NodeType.ITERATOR_COMPONENT;
        }
        return NodeType.UNKNOWN;
    }

    /**
     * 查找XML中定义的脚本节点。
     */
    private List<LiteFlowNodeInfo> findXmlScriptNodes(@NotNull Project project) {
        List<LiteFlowNodeInfo> xmlNodeInfos = new ArrayList<>();
        Collection<VirtualFile> virtualFiles = FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project));
        PsiManager psiManager = PsiManager.getInstance(project);

        for (VirtualFile virtualFile : virtualFiles) {
            if (project.isDisposed()) {
                break;
            }
            PsiFile psiFile = psiManager.findFile(virtualFile);
            if (psiFile instanceof XmlFile) {
                XmlFile xmlFile = (XmlFile) psiFile;
                // [核心修改] 先使用 isLiteFlowXml 进行判断
                if (LiteFlowXmlUtil.isLiteFlowXml(xmlFile)) {
                    // 判断通过后，可以安全地获取根标签进行处理
                    XmlTag flowRootTag = xmlFile.getDocument().getRootTag();
                    if (flowRootTag == null) {
                        continue; // 添加一个防御性检查
                    }

                    XmlTag nodesTag = LiteFlowXmlUtil.getNodesTag(flowRootTag);
                    if (nodesTag != null) {
                        for (XmlTag nodeTag : nodesTag.findSubTags("node")) {
                            if (project.isDisposed()) {
                                return Collections.emptyList();
                            }

                            String xmlId = nodeTag.getAttributeValue("id");
                            String xmlName = nodeTag.getAttributeValue("name");
                            String typeAttr = nodeTag.getAttributeValue("type");

                            // [优化] 使用 StringUtil 统一空值检查
                            if (StringUtil.isEmpty(xmlId)) {
                                LOG.warn("跳过XML节点，缺少'id'属性: " + nodeTag.getName() + " in " + xmlFile.getName());
                                continue;
                            }

                            NodeType nodeType = NodeType.fromXmlType(typeAttr);
                            xmlNodeInfos.add(new LiteFlowNodeInfo(xmlId, xmlName, nodeType, nodeTag, "XML脚本"));
                        }
                    }
                }
            }
        }
        return xmlNodeInfos;
    }
}