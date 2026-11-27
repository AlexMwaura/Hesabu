package ke.hesabu.server.catalogue

import ke.hesabu.core.TaxType
import ke.hesabu.core.etims.ItemCode
import ke.hesabu.core.sync.CatalogueItem
import ke.hesabu.core.sync.CataloguePage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class ProductInput(
    /** Leave out to have Hesabu generate a KRA-format item code (VSCU spec 4.17). */
    val code: String? = null,
    val itemClassCode: String,
    val name: String,
    val barcode: String? = null,
    val priceCents: Long,
    val taxType: TaxType,
    val isService: Boolean = false,
    val qtyUnitCode: String = "U",
    val pkgUnitCode: String = "NT",
    val reorderLevelMilli: Long = 0,
    val active: Boolean = true,
)

/** The business's catalogue. Every change bumps a global version so hubs pull only what changed. */
@Service
class CatalogueService(private val jdbc: JdbcClient) {

    @Transactional
    fun upsert(businessId: UUID, input: ProductInput): CatalogueItem {
        require(input.name.isNotBlank()) { "Product name is required" }
        require(input.priceCents >= 0) { "Price can't be negative" }
        // TODO(kra-sandbox): validate itemClassCode against KRA's item classification list from the VSCU.
        require(input.itemClassCode.isNotBlank()) { "KRA item classification code is required" }
        val code = input.code ?: ItemCode.of(
            jdbc.sql("SELECT nextval('item_serial_seq')").query { rs, _ -> rs.getLong(1) }.single(),
            productType = if (input.isService) 3 else 2, pkgUnit = input.pkgUnitCode, qtyUnit = input.qtyUnitCode,
        )
        jdbc.sql(
            """
            INSERT INTO product (business_id, code, item_class_code, name, barcode, price_cents, tax_type, is_service,
                                 qty_unit_code, pkg_unit_code, reorder_level_milli, active, version, updated_at)
            VALUES (:b, :code, :cls, :name, :bc, :price, :tax, :svc, :qu, :pu, :rl, :active, nextval('product_version_seq'), now())
            ON CONFLICT (business_id, code) DO UPDATE SET
                item_class_code = excluded.item_class_code, name = excluded.name, barcode = excluded.barcode,
                price_cents = excluded.price_cents, tax_type = excluded.tax_type, is_service = excluded.is_service,
                qty_unit_code = excluded.qty_unit_code, pkg_unit_code = excluded.pkg_unit_code,
                reorder_level_milli = excluded.reorder_level_milli, active = excluded.active,
                version = nextval('product_version_seq'), updated_at = now()
            """.trimIndent(),
        )
            .param("b", businessId).param("code", code).param("cls", input.itemClassCode).param("name", input.name.trim())
            .param("bc", input.barcode).param("price", input.priceCents).param("tax", input.taxType.name)
            .param("svc", input.isService).param("qu", input.qtyUnitCode).param("pu", input.pkgUnitCode)
            .param("rl", input.reorderLevelMilli).param("active", input.active)
            .update()
        return since(businessId, 0).items.first { it.code == code }
    }

    fun since(businessId: UUID, version: Long): CataloguePage {
        val items = jdbc.sql("SELECT * FROM product WHERE business_id = :b AND version > :v ORDER BY version")
            .param("b", businessId).param("v", version)
            .query { rs, _ ->
                CatalogueItem(
                    rs.getString("code"), rs.getString("item_class_code"), rs.getString("name"), rs.getString("barcode"),
                    rs.getLong("price_cents"), rs.getString("tax_type"), rs.getBoolean("is_service"), rs.getString("qty_unit_code"),
                    rs.getString("pkg_unit_code"), rs.getLong("reorder_level_milli"), rs.getBoolean("active"), rs.getLong("version"),
                )
            }.list()
        return CataloguePage(items, items.maxOfOrNull { it.version } ?: version)
    }
}
