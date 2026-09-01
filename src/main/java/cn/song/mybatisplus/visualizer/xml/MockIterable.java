package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 求值器的模拟可迭代值（T-M24-02）：foreach 展开 N 次，元素为无属性 MockObject。
 */
public class MockIterable implements Iterable<MockObject> {

    private final int size;

    public MockIterable(int size) {
        this.size = size;
    }

    /** 展开次数。 */
    public int getSize() {
        return size;
    }

    @Override
    public Iterator<MockObject> iterator() {
        List<MockObject> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(new MockObject(Collections.emptyMap()));
        }
        return list.iterator();
    }
}
