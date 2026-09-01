package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper 上的一次方法调用。
 *
 * @param methodName 方法名（如 eq、select、orderByDesc）
 * @param args       实参（psi 层已解析为 WrapperArg）
 */
public final class WrapperCall {
    private final String methodName;
    private final List<WrapperArg> args;

    public WrapperCall(String methodName, List<WrapperArg> args) {
        this.methodName = methodName;
        this.args = args;
    }

    public String getMethodName() {
        return methodName;
    }

    public List<WrapperArg> getArgs() {
        return args;
    }

    public WrapperCall copy(String methodName, List<WrapperArg> args) {
        return new WrapperCall(methodName, args);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WrapperCall)) {
            return false;
        }
        WrapperCall that = (WrapperCall) o;
        return Objects.equals(methodName, that.methodName) && Objects.equals(args, that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, args);
    }

    @Override
    public String toString() {
        return "WrapperCall(methodName=" + methodName + ", args=" + args + ")";
    }
}
