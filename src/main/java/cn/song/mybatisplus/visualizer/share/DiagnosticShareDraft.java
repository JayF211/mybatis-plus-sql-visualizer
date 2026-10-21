// 自 master share/DiagnosticShareDraft.kt 移植（R94 诊断草稿审阅）
package cn.song.mybatisplus.visualizer.share;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A local editable draft. Consent authorizes exactly one snapshot, not future edits or retries.
 *
 * <p>R94：诊断内容先进草稿——分段可勾选、正文可编辑，每次改动都撤销已给出的同意；
 * 只有「已同意且内容未再变」才允许取出一次快照提交（{@link #takeSubmission()} 取出即清同意）。</p>
 */
public final class DiagnosticShareDraft {

    /** 一个可勾选/可省略的诊断段。 */
    public record Section(@NotNull String id, @NotNull String title, @NotNull String text) {
    }

    /** 一次已审阅快照（描述 + UTF-8 字节数）。 */
    public record Snapshot(@NotNull String description, int byteSize) {
    }

    private final List<Section> sections;
    private final Set<String> included;
    private String body;
    private String approvedBody = null;

    public DiagnosticShareDraft(@NotNull List<Section> sections) {
        this.sections = List.copyOf(sections);
        Set<String> ids = new LinkedHashSet<>();
        for (Section section : this.sections) {
            if (!ids.add(section.id())) {
                throw new IllegalArgumentException("duplicate section id: " + section.id());
            }
        }
        this.included = new LinkedHashSet<>(ids);
        this.body = render();
    }

    public List<Section> getSections() {
        return sections;
    }

    public String getBody() {
        return body;
    }

    public String body() {
        return body;
    }

    public void include(@NotNull String id, boolean enabled) {
        boolean known = false;
        for (Section section : sections) {
            if (section.id().equals(id)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new IllegalArgumentException("unknown section id: " + id);
        }
        if (enabled) {
            included.add(id);
        } else {
            included.remove(id);
        }
        body = render();
        approvedBody = null;
    }

    public void edit(@NotNull String text) {
        body = text;
        approvedBody = null;
    }

    public void approve(boolean approved) {
        approvedBody = approved ? body : null;
    }

    /** 已同意且内容未再变。 */
    public boolean isApproved() {
        return approvedBody != null && approvedBody.equals(body);
    }

    public boolean getApproved() {
        return isApproved();
    }

    public int getByteSize() {
        return body.getBytes(StandardCharsets.UTF_8).length;
    }

    public int byteSize() {
        return getByteSize();
    }

    @Nullable
    public Snapshot takeSubmission() {
        if (!isApproved() || body.isBlank() || getByteSize() > ShareContentProjection.MAX_OUTPUT_BYTES) {
            return null;
        }
        Snapshot result = new Snapshot(body, getByteSize());
        approvedBody = null;
        return result;
    }

    private String render() {
        List<String> parts = new ArrayList<>();
        for (Section section : sections) {
            if (included.contains(section.id())) {
                parts.add("## " + section.title() + "\n" + section.text());
            }
        }
        return String.join("\n\n", parts);
    }
}
