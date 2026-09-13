package com.retailsuite.common;

import java.util.List;

/** 统一分页结果（前端表格组件直接用 total + records）。 */
public record PageResult<T>(long total, long page, long size, List<T> records) {

    public static <T> PageResult<T> of(long total, long page, long size, List<T> records) {
        return new PageResult<>(total, page, size, List.copyOf(records));
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(0, page, size, List.of());
    }
}
