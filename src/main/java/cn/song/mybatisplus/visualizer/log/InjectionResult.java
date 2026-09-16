package cn.song.mybatisplus.visualizer.log;

/**
 * F-44-7：SqlSourceInterceptor 自动注入结果。
 * 对应 master 的 InjectionResult（sealed class）。Java 端用抽象类 + 静态子类模拟。
 */
public abstract class InjectionResult {

    private InjectionResult() {
    }

    /** 注入成功（多模块时消息含成功模块清单）。 */
    public static final class Success extends InjectionResult {
        private final String message;

        public Success(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "Success{message=" + message + "}";
        }
    }

    /** 项目类型不支持。 */
    public static final class UnsupportedProject extends InjectionResult {
        public static final UnsupportedProject INSTANCE = new UnsupportedProject();

        private UnsupportedProject() {
        }
    }

    /** 无法检测项目结构。 */
    public static final class CannotDetectStructure extends InjectionResult {
        public static final CannotDetectStructure INSTANCE = new CannotDetectStructure();

        private CannotDetectStructure() {
        }
    }

    /** 文件已存在（幂等跳过）。 */
    public static final class AlreadyExists extends InjectionResult {
        public static final AlreadyExists INSTANCE = new AlreadyExists();

        private AlreadyExists() {
        }
    }

    /** 用户在模块选择弹框取消（T-ModPick：多模块工程不再全量注入，先让用户勾选）。 */
    public static final class Cancelled extends InjectionResult {
        public static final Cancelled INSTANCE = new Cancelled();

        private Cancelled() {
        }
    }
}
