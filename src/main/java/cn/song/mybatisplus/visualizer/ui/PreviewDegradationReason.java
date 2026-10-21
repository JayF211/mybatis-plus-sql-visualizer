// 自 master ui/PreviewDegradationReason.kt 移植（T-DegradedPreviewUx P0：预览降级原因分型）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-DegradedPreviewUx P0：预览降级原因。
 *
 * <p>此前「预热超时 / 项目内文件建不出来 / VFS 刷新失败 / PSI 解析失败 / 产物已被消费」全部压成
 * 同一条 {@code panel.banner.degradedPsi}，横幅把「慢」和「故障」说成同一句话（用户 2026-10-20 截图：
 * 首开预热超预算即挂红色「本次项目内临时文件不可用」）。分型后由 {@link #bannerKey()}/{@link #causeKey()}
 * 选文案，并且 {@link #TIMEOUT} 是<b>瞬时态</b>——产物仍在途，走宽限期延迟披露
 * （见 {@code SqlPreviewPanel.showDegradedBanner}）。</p>
 *
 * <p>纯枚举、零平台依赖（便于 headless 断言）。</p>
 */
public enum PreviewDegradationReason {

    /** 预热超预算、产物仍在途（宽限期内到达即静默换装，横幅不出现）。 */
    TIMEOUT,

    /** {@code projectPreviewFile()} 返回 null：项目/{@code basePath} 为空或 {@code .idea/sql-visualizer/preview} 建不出来。 */
    FILE_UNAVAILABLE,

    /** {@code LocalFileSystem.refreshAndFindFileByIoFile} 重试后仍为 null。 */
    VFS_UNAVAILABLE,

    /** {@code PsiManager.findFile} 返回 null。 */
    PSI_UNAVAILABLE,

    /** 预热线程内未归类的异常（方言映射/读动作等服务调用抛出）。 */
    PREWARM_ERROR,

    /** 预热产物已被上一次装配消费（同面板二次装配、未重新预热）。 */
    PRODUCT_CONSUMED,

    /** 第二级「内存 PSI」自身创建失败，落到第三级裸文档。 */
    MEMORY_PSI_FAILED;

    /** 横幅正文键：超时是「正在恢复」，其余是「已失败」；第三级连 PSI 载体都没有，沿用裸文档文案。 */
    @NotNull
    public String bannerKey() {
        switch (this) {
            case TIMEOUT:
                return "panel.banner.degradedTimeout";
            case MEMORY_PSI_FAILED:
                return "panel.banner.degradedPlain";
            default:
                return "panel.banner.degradedFailed";
        }
    }

    /** 失败原因短句键（null = 该原因的正文已是完整句子，不需要 {@code {0}} 占位）。 */
    @Nullable
    public String causeKey() {
        switch (this) {
            case TIMEOUT:
            case MEMORY_PSI_FAILED:
                return null;
            case FILE_UNAVAILABLE:
                return "panel.banner.degradedCause.file";
            case VFS_UNAVAILABLE:
                return "panel.banner.degradedCause.vfs";
            case PSI_UNAVAILABLE:
                return "panel.banner.degradedCause.psi";
            case PREWARM_ERROR:
                return "panel.banner.degradedCause.prewarm";
            case PRODUCT_CONSUMED:
                return "panel.banner.degradedCause.consumed";
            default:
                return null;
        }
    }
}
