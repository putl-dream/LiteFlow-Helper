package com.yomahub.liteflowhelper.toolwindow.service;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.yomahub.liteflowhelper.toolwindow.model.ChainInfo;
import com.yomahub.liteflowhelper.utils.LiteFlowXmlUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 负责扫描项目中的 LiteFlow Chain (流程链) 定义。
 * Chain通常在XML配置文件中定义。
 */
public class LiteFlowChainScanner {
    private static final Logger LOG = Logger.getInstance(LiteFlowChainScanner.class);

    /**
     * 在项目中查找所有的 LiteFlow Chain 定义。
     * @param project 当前项目
     * @return ChainInfo 列表。如果项目处于Dumb Mode则返回空列表。
     */
    public List<ChainInfo> findChains(Project project) {
        return ScannerUtil.runInReadAction(project, "LiteFlow Chains", () -> {
            List<ChainInfo> chainInfos = new ArrayList<>();
            Collection<VirtualFile> virtualFiles = FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project));
            PsiManager psiManager = PsiManager.getInstance(project);

            // [优化] 调整为debug级别，减少生产环境日志噪音
            LOG.debug("找到 " + virtualFiles.size() + " 个 XML 文件");
            int liteFlowXmlCount = 0;

            for (VirtualFile virtualFile : virtualFiles) {
                if (project.isDisposed()) {
                    return Collections.emptyList();
                }
                PsiFile psiFile = psiManager.findFile(virtualFile);
                if (psiFile instanceof XmlFile) {
                    XmlFile xmlFile = (XmlFile) psiFile;
                    // [核心修改] 先使用 isLiteFlowXml 进行判断
                    if (LiteFlowXmlUtil.isLiteFlowXml(xmlFile)) {
                        liteFlowXmlCount++;
                        // 判断通过后，可以安全地获取根标签进行处理
                        XmlTag flowRootTag = xmlFile.getDocument().getRootTag();
                        if (flowRootTag == null) {
                            continue; // 添加一个防御性检查
                        }

                        // <flow> 标签下的 <chain> 标签代表一个流程链
                        XmlTag[] chainTags = flowRootTag.findSubTags("chain");
                        for (XmlTag chainTag : chainTags) {
                            if (project.isDisposed()) { // 在循环中也检查项目状态
                                return Collections.emptyList();
                            }
                            String chainId = chainTag.getAttributeValue("id");
                            String chainName = chainTag.getAttributeValue("name"); // LiteFlow 中 chain 更常用 'name' 属性
                            String finalName = null;

                            // [优化] 使用 StringUtil 统一空值检查
                            // 优先使用 chain 的 'name' 属性作为显示名称，这是LiteFlow中更常见的做法
                            if (!StringUtil.isEmpty(chainName)) {
                                finalName = chainName;
                            } else if (!StringUtil.isEmpty(chainId)) {
                                finalName = chainId; // 如果 'name' 属性不存在，则使用 'id'
                            }

                            if (!StringUtil.isEmpty(finalName)) {
                                int offset = chainTag.getTextOffset(); // 获取标签在文件中的偏移量，用于导航
                                chainInfos.add(new ChainInfo(finalName, xmlFile, offset));
                            }
                        }
                    }
                }
            }

            // [优化] 调整为debug级别
            LOG.debug("其中 " + liteFlowXmlCount + " 个是 LiteFlow XML 配置文件");
            chainInfos.sort(Comparator.comparing(ChainInfo::getName));
            return chainInfos;
        });
    }
}
