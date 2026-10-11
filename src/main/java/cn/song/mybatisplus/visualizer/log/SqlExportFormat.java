package cn.song.mybatisplus.visualizer.log;

/** 导出格式（{@link SqlExportService} 分发）。 */
public enum SqlExportFormat {
    CSV("csv"),
    TSV("tsv"),
    TXT("txt"),
    SQL("sql");

    private final String ext;

    SqlExportFormat(String ext) {
        this.ext = ext;
    }

    /** 文件扩展名（不含点）。 */
    public String getExt() {
        return ext;
    }
}
