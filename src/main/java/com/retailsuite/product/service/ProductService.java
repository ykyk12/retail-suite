package com.retailsuite.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.retailsuite.common.BizException;
import com.retailsuite.common.ErrorCode;
import com.retailsuite.common.PageResult;
import com.retailsuite.config.AppProperties;
import com.retailsuite.inventory.service.InventoryService;
import com.retailsuite.product.dto.ProductDtos;
import com.retailsuite.product.entity.Product;
import com.retailsuite.product.entity.ProductCategory;
import com.retailsuite.product.mapper.ProductCategoryMapper;
import com.retailsuite.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商品与分类服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final InventoryService inventoryService;
    private final AppProperties properties;

    public PageResult<ProductDtos.View> page(Long storeId, String keyword, Long categoryId,
                                             Boolean lowStockOnly, long page, long size) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Product::getName, keyword)
                        .or().like(Product::getBarcode, keyword))
                // 低库存筛选：列与列比较只能用 apply（这里是静态 SQL，无注入风险）
                .apply(Boolean.TRUE.equals(lowStockOnly), "stock <= low_stock_threshold")
                .orderByDesc(Product::getId);

        Page<Product> result = productMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)), wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                toViews(storeId, result.getRecords()));
    }

    public ProductDtos.View detail(Long storeId, Long productId) {
        return toView(storeId, requireProduct(storeId, productId), null);
    }

    /** 收银台扫码：条码命中即可加入购物车；未命中返回 404，由前端提示"未找到商品，是否建档"。 */
    public ProductDtos.View findByBarcode(Long storeId, String barcode) {
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .eq(Product::getBarcode, barcode));
        if (product == null) {
            throw BizException.notFound("条码「" + barcode + "」对应的商品");
        }
        return toView(storeId, product, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductDtos.View create(Long storeId, ProductDtos.CreateRequest request) {
        validatePrice(request.salePrice(), request.purchasePrice());
        Product product = new Product();
        product.setStoreId(storeId);
        product.setCategoryId(request.categoryId());
        product.setName(request.name().trim());
        product.setBarcode(blankToNull(request.barcode()));
        product.setSpec(request.spec());
        product.setUnit(request.unit());
        product.setPurchasePrice(request.purchasePrice());
        product.setSalePrice(request.salePrice());
        // 库存一律从 0 起，再通过库存服务入库：这样"期初库存"也有一条流水，账实可对
        product.setStock(0);
        product.setLowStockThreshold(request.lowStockThreshold() == null
                ? properties.getInventory().getDefaultLowStockThreshold() : request.lowStockThreshold());
        // 保质期天数：空表示不追踪（日用品），有值时入库会按生产日期推算到期日
        product.setShelfLifeDays(request.shelfLifeDays());
        product.setStatus(1);
        product.setVersion(0);
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        product.setDeleted(0);
        try {
            productMapper.insert(product);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "条码「" + product.getBarcode() + "」已被其他商品占用");
        }
        if (request.initStock() != null && request.initStock() > 0) {
            inventoryService.increase(storeId, product.getId(), request.initStock(), "MANUAL", null, "期初建库");
        }
        return detail(storeId, product.getId());
    }

    /** 修改商品：不含库存（库存只能通过库存服务变动，防止账实不符）。 */
    @Transactional(rollbackFor = Exception.class)
    public ProductDtos.View update(Long storeId, Long productId, ProductDtos.UpdateRequest request) {
        Product existing = requireProduct(storeId, productId);
        validatePrice(request.salePrice(), request.purchasePrice());
        Product update = new Product();
        update.setId(existing.getId());
        update.setCategoryId(request.categoryId());
        update.setName(request.name().trim());
        update.setBarcode(blankToNull(request.barcode()));
        update.setSpec(request.spec());
        update.setUnit(request.unit());
        update.setPurchasePrice(request.purchasePrice());
        update.setSalePrice(request.salePrice());
        if (request.lowStockThreshold() != null) {
            update.setLowStockThreshold(request.lowStockThreshold());
        }
        update.setShelfLifeDays(request.shelfLifeDays());
        if (request.status() != null) {
            update.setStatus(request.status());
        }
        update.setUpdatedAt(LocalDateTime.now());
        try {
            productMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "条码已被其他商品占用");
        }
        return detail(storeId, productId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long storeId, Long productId, Integer status) {
        requireProduct(storeId, productId);
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 0（停售）或 1（在售）");
        }
        Product update = new Product();
        update.setId(productId);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        productMapper.updateById(update);
    }

    // ------------------------------------------------------------------ 分类

    public List<ProductDtos.CategoryView> categories(Long storeId) {
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getStoreId, storeId)
                .orderByAsc(ProductCategory::getSortNo)
                .orderByAsc(ProductCategory::getId));
        List<ProductDtos.CategoryView> views = new ArrayList<>(categories.size());
        for (ProductCategory category : categories) {
            views.add(new ProductDtos.CategoryView(category.getId(), category.getName(),
                    category.getSortNo(), category.getStatus()));
        }
        return views;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductDtos.CategoryView createCategory(Long storeId, ProductDtos.CategoryRequest request) {
        ProductCategory category = new ProductCategory();
        category.setStoreId(storeId);
        category.setName(request.name().trim());
        category.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        category.setStatus(1);
        category.setCreatedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());
        category.setDeleted(0);
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "分类「" + category.getName() + "」已存在");
        }
        return new ProductDtos.CategoryView(category.getId(), category.getName(),
                category.getSortNo(), category.getStatus());
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(Long storeId, Long categoryId, ProductDtos.CategoryRequest request) {
        ProductCategory existing = requireCategory(storeId, categoryId);
        ProductCategory update = new ProductCategory();
        update.setId(existing.getId());
        update.setName(request.name().trim());
        if (request.sortNo() != null) {
            update.setSortNo(request.sortNo());
        }
        update.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(update);
    }

    /** 删除分类：有商品在用则拒绝（否则商品会指向一个不存在的分类）。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long storeId, Long categoryId) {
        requireCategory(storeId, categoryId);
        long used = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getStoreId, storeId)
                .eq(Product::getCategoryId, categoryId));
        if (used > 0) {
            throw new BizException(ErrorCode.CONFLICT, "该分类下还有 " + used + " 个商品，请先转移或删除商品");
        }
        categoryMapper.deleteById(categoryId);
    }

    // ------------------------------------------------------------------ 内部

    public Product requireProduct(Long storeId, Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !product.getStoreId().equals(storeId)) {
            throw BizException.notFound("商品");
        }
        return product;
    }

    private ProductCategory requireCategory(Long storeId, Long categoryId) {
        ProductCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !category.getStoreId().equals(storeId)) {
            throw BizException.notFound("分类");
        }
        return category;
    }

    private void validatePrice(BigDecimal salePrice, BigDecimal purchasePrice) {
        if (salePrice == null || purchasePrice == null) {
            return;
        }
        if (salePrice.compareTo(purchasePrice) < 0) {
            // 只提示不拦截：临期清仓确实可能低于进价，但要让操作者知道自己在亏本卖
            log.info("商品售价 {} 低于进价 {}", salePrice, purchasePrice);
        }
    }

    /** 一次性取分类名，避免每行都查一次库（N+1 查询）。 */
    private List<ProductDtos.View> toViews(Long storeId, List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNames = new HashMap<>();
        for (ProductCategory category : categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getStoreId, storeId))) {
            categoryNames.put(category.getId(), category.getName());
        }
        List<ProductDtos.View> views = new ArrayList<>(products.size());
        for (Product product : products) {
            views.add(toView(storeId, product, categoryNames));
        }
        return views;
    }

    private ProductDtos.View toView(Long storeId, Product product, Map<Long, String> categoryNames) {
        String categoryName = null;
        if (product.getCategoryId() != null) {
            if (categoryNames != null) {
                categoryName = categoryNames.get(product.getCategoryId());
            } else {
                ProductCategory category = categoryMapper.selectById(product.getCategoryId());
                categoryName = category == null ? null : category.getName();
            }
        }
        int stock = product.getStock() == null ? 0 : product.getStock();
        int threshold = product.getLowStockThreshold() == null ? 0 : product.getLowStockThreshold();
        return new ProductDtos.View(product.getId(), product.getName(), product.getCategoryId(), categoryName,
                product.getBarcode(), product.getSpec(), product.getUnit(), product.getPurchasePrice(),
                product.getSalePrice(), stock, threshold, product.getShelfLifeDays(), stock <= threshold,
                product.getStatus(), product.getUpdatedAt());
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 供报表/看板用：门店商品总数。 */
    public long countByStore(Long storeId) {
        return productMapper.selectCount(new LambdaQueryWrapper<Product>().eq(Product::getStoreId, storeId));
    }

    /** 供内部调试与测试查看字段清单（防止有人误加 stock 到更新 DTO）。 */
    public Map<String, Object> schemaHint() {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("updateFieldsImmutable", List.of("stock", "storeId", "createdAt"));
        hint.put("stockChangedOnlyBy", "InventoryService");
        return hint;
    }
}
