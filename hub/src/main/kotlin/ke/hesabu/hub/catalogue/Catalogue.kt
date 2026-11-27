package ke.hesabu.hub.catalogue

import ke.hesabu.core.TaxType
import ke.hesabu.core.sync.CatalogueItem
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class Product(
    val code: String,
    val itemClassCode: String,
    val name: String,
    val barcode: String?,
    val priceCents: Long,
    val taxType: TaxType,
    val isService: Boolean,
    val qtyUnitCode: String,
    val pkgUnitCode: String,
    val reorderLevelMilli: Long,
    val active: Boolean,
    val version: Long,
) {
    companion object {
        fun of(i: CatalogueItem) = Product(
            i.code, i.itemClassCode, i.name, i.barcode, i.priceCents, TaxType.valueOf(i.taxType), i.isService,
            i.qtyUnitCode, i.pkgUnitCode, i.reorderLevelMilli, i.active, i.version,
        )
    }
}

/** The branch's copy of the catalogue. The cloud owns it; the hub pulls changes by version. */
@Repository
class CatalogueRepo(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Product(
            code = rs.getString("code"),
            itemClassCode = rs.getString("item_class_code"),
            name = rs.getString("name"),
            barcode = rs.getString("barcode"),
            priceCents = rs.getLong("price_cents"),
            taxType = TaxType.valueOf(rs.getString("tax_type")),
            isService = rs.getInt("is_service") == 1,
            qtyUnitCode = rs.getString("qty_unit_code"),
            pkgUnitCode = rs.getString("pkg_unit_code"),
            reorderLevelMilli = rs.getLong("reorder_level_milli"),
            active = rs.getInt("active") == 1,
            version = rs.getLong("version"),
        )
    }

    fun upsert(p: Product) {
        jdbc.sql(
            """
            INSERT INTO product (code, item_class_code, name, barcode, price_cents, tax_type, is_service,
                                 qty_unit_code, pkg_unit_code, reorder_level_milli, active, version)
            VALUES (:code, :cls, :name, :barcode, :price, :tax, :service, :qtyUnit, :pkgUnit, :reorder, :active, :version)
            ON CONFLICT (code) DO UPDATE SET
                item_class_code = excluded.item_class_code, name = excluded.name, barcode = excluded.barcode,
                price_cents = excluded.price_cents, tax_type = excluded.tax_type, is_service = excluded.is_service,
                qty_unit_code = excluded.qty_unit_code, pkg_unit_code = excluded.pkg_unit_code,
                reorder_level_milli = excluded.reorder_level_milli, active = excluded.active, version = excluded.version
            """.trimIndent(),
        )
            .param("code", p.code).param("cls", p.itemClassCode).param("name", p.name).param("barcode", p.barcode)
            .param("price", p.priceCents).param("tax", p.taxType.name).param("service", if (p.isService) 1 else 0)
            .param("qtyUnit", p.qtyUnitCode).param("pkgUnit", p.pkgUnitCode).param("reorder", p.reorderLevelMilli)
            .param("active", if (p.active) 1 else 0).param("version", p.version)
            .update()
        jdbc.sql("INSERT INTO stock (product_code, on_hand_milli) VALUES (:c, 0) ON CONFLICT (product_code) DO NOTHING")
            .param("c", p.code).update()
    }

    fun find(code: String): Product? =
        jdbc.sql("SELECT * FROM product WHERE code = :c").param("c", code).query(mapper).optional().orElse(null)

    fun findByBarcode(barcode: String): Product? =
        jdbc.sql("SELECT * FROM product WHERE barcode = :b AND active = 1").param("b", barcode).query(mapper).list().firstOrNull()

    fun all(): List<Product> = jdbc.sql("SELECT * FROM product WHERE active = 1 ORDER BY name").query(mapper).list()

    fun maxVersion(): Long = jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM product").query { rs, _ -> rs.getLong(1) }.single()
}
