package com.retailsuite.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品相关 DTO（集中放一处，方便对照接口字段与校验规则）。 */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "商品名称不能为空")
            @Size(max = 128, message = "商品名称过长")
            String name,
            Long categoryId,
            @Size(max = 64, message = "条码过长")
            String barcode,
            @Size(max = 64, message = "规格过长")
            String spec,
            @Size(max = 16, message = "单位过长")
            String unit,
            @NotNull(message = "进价不能为空")
            @DecimalMin(value = "0", message = "进价不能为负")
            BigDecimal purchasePrice,
            @NotNull(message = "售价不能为空")
            @DecimalMin(value = "0", message = "售价不能为负")
            BigDecimal salePrice,
            /** 期初库存：会同步写一条库存流水，保证"库存有账可查" */
            @Min(value = 0, message = "期初库存不能为负")
            Integer initStock,
            @Min(value = 0, message = "预警阈值不能为负")
            Integer lowStockThreshold,
            /** 保质期天数：留空表示不追踪保质期（日用品）；有值时入库会按生产日期推算到期日 */
            @Min(value = 0, message = "保质期天数不能为负")
            Integer shelfLifeDays) {

        /** 兼容不追踪保质期的调用（等价于 shelfLifeDays = null）。 */
        public CreateRequest(String name, Long categoryId, String barcode, String spec, String unit,
                             BigDecimal purchasePrice, BigDecimal salePrice, Integer initStock,
                             Integer lowStockThreshold) {
            this(name, categoryId, barcode, spec, unit, purchasePrice, salePrice, initStock,
                    lowStockThreshold, null);
        }
    }

    /** 修改商品：**刻意不含 stock**——库存只能通过库存服务变动，避免"改商品顺手把库存改了"这种账实不符。 */
    public record UpdateRequest(
            @NotBlank(message = "商品名称不能为空")
            @Size(max = 128, message = "商品名称过长")
            String name,
            Long categoryId,
            @Size(max = 64, message = "条码过长")
            String barcode,
            @Size(max = 64, message = "规格过长")
            String spec,
            @Size(max = 16, message = "单位过长")
            String unit,
            @NotNull(message = "进价不能为空")
            @DecimalMin(value = "0", message = "进价不能为负")
            BigDecimal purchasePrice,
            @NotNull(message = "售价不能为空")
            @DecimalMin(value = "0", message = "售价不能为负")
            BigDecimal salePrice,
            @Min(value = 0, message = "预警阈值不能为负")
            Integer lowStockThreshold,
            Integer shelfLifeDays,
            Integer status) {

        /** 兼容不带保质期与状态的调用。 */
        public UpdateRequest(String name, Long categoryId, String barcode, String spec, String unit,
                             BigDecimal purchasePrice, BigDecimal salePrice, Integer lowStockThreshold,
                             Integer status) {
            this(name, categoryId, barcode, spec, unit, purchasePrice, salePrice, lowStockThreshold, null, status);
        }
    }

    public record View(Long id,
                       String name,
                       Long categoryId,
                       String categoryName,
                       String barcode,
                       String spec,
                       String unit,
                       BigDecimal purchasePrice,
                       BigDecimal salePrice,
                       Integer stock,
                       Integer lowStockThreshold,
                       Integer shelfLifeDays,
                       boolean lowStock,
                       Integer status,
                       LocalDateTime updatedAt) {
    }

    public record CategoryRequest(
            @NotBlank(message = "分类名称不能为空")
            @Size(max = 64, message = "分类名称过长")
            String name,
            Integer sortNo) {
    }

    public record CategoryView(Long id, String name, Integer sortNo, Integer status) {
    }
}
