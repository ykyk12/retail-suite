package com.retailsuite.report.excel;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Excel 导出（POI 流式）。
 *
 * 为什么用 SXSSFWorkbook 而不是 XSSFWorkbook：
 * XSSF 会把整张表放在内存里，导出几万行就可能 OOM；SXSSF 只在内存里保留滑动窗口（这里 100 行），
 * 其余边写边刷到磁盘临时文件——这是"大数据量导出"的标准做法。
 * 用完必须 dispose() 清理临时文件，否则磁盘会被悄悄占满。
 */
@Slf4j
@Component
public class ExcelExporter {

    private static final int ROW_ACCESS_WINDOW_SIZE = 100;

    public void write(OutputStream out, String sheetName, List<String> headers, List<List<Object>> rows)
            throws IOException {
        long start = System.currentTimeMillis();
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW_SIZE);
        try {
            Sheet sheet = workbook.createSheet(sheetName == null ? "Sheet1" : sheetName);
            CellStyle headerStyle = headerStyle(workbook);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }

            int rowIndex = 1;
            for (List<Object> rowData : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < rowData.size(); i++) {
                    Object value = rowData.get(i);
                    setCellValue(row.createCell(i), value);
                }
            }
            workbook.write(out);
            out.flush();
            log.info("Excel 导出完成 sheet={} 行数={} 耗时={}ms", sheetName, rows.size(),
                    System.currentTimeMillis() - start);
        } finally {
            // 清理 SXSSF 的临时文件
            workbook.dispose();
            workbook.close();
        }
    }

    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
