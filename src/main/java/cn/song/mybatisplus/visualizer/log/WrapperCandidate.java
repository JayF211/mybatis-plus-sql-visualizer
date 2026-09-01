package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;

/**
 * 候选跳转目标。
 *
 * @param anchorMethod Wrapper 链锚点所在方法。
 * @param file         所在文件。
 * @param table        匹配到的表信息。
 */
public record WrapperCandidate(
        PsiMethod anchorMethod,
        PsiFile file,
        TableInfo table
) {}
