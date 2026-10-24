package modules.bxxtdb;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.DateTime;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.QueryColumnType;
import ortus.boxlang.runtime.types.Struct;

class XTDBValuesTest {
    private final XTDBDriver driver = new XTDBDriver();

    @Test void convertsNestedRecordsWithoutCollapsingCaseOrNulls() {
        var input = new LinkedHashMap<String, Object>();
        input.put("Name", Arrays.asList(null, true));
        input.put("name", Map.of("amount", new BigDecimal("12345678901234567890.123456789")));
        var result = (IStruct) driver.transformValue(Types.OTHER, input, null);
        assertEquals(2, result.size());
        var list = result.getAsArray(Key.of("Name"));
        assertNull(list.get(0));
        assertEquals(true, list.get(1));
        assertEquals(new BigDecimal("12345678901234567890.123456789"), result.getAsStruct(Key.of("name")).get("amount"));
    }

    @Test void preservesSpecializedValuesAndBooleans() {
        for (Object value : List.of(LocalDate.now(), LocalTime.now(), LocalDateTime.now(), UUID.randomUUID(),
                Set.of("x"), new byte[] {1, 2}, Map.of(1, "non-string key"))) {
            assertSame(value, driver.transformValue(Types.OTHER, value, null));
        }
        assertEquals(true, driver.transformValue(Types.BIT, true, null));
        assertNull(driver.transformValue(Types.OTHER, null, null));
    }

    @Test void roundTripsZonedTimestampsWithoutTruncation() {
        var date = ZonedDateTime.parse("2026-01-02T03:04:05.123456789+01:00[Europe/Paris]");
        var result = (DateTime) driver.transformValue(Types.TIMESTAMP_WITH_TIMEZONE, date, null);
        assertEquals(date, result.getWrapped());
        assertEquals(date, driver.transformParamValue(QueryColumnType.OTHER, result, null, null));
    }

    @Test void copiesBoxLangCollectionsAndUnwrapsKeysAndNulls() {
        var list = Array.of(1, null, new DateTime(ZonedDateTime.parse("2026-01-01T00:00Z")));
        IStruct input = Struct.of("Items", list);
        var result = (Map<?, ?>) driver.transformParamValue(QueryColumnType.OTHER, input, null, null);
        assertTrue(result.containsKey("Items"));
        var copy = (List<?>) result.get("Items");
        assertNull(copy.get(1));
        assertInstanceOf(ZonedDateTime.class, copy.get(2));
        assertInstanceOf(DateTime.class, list.get(2));
        assertNotSame(input, result);
        assertNotSame(list, copy);
    }

    @Test void rejectsCyclesButAllowsSharedChildren() {
        var child = new ArrayList<Object>();
        child.add(1);
        assertEquals(List.of(List.of(1), List.of(1)), XTDBValues.toJdbc(List.of(child, child)));
        child.add(child);
        assertThrows(IllegalArgumentException.class, () -> XTDBValues.toJdbc(child));
        var struct = new Struct();
        struct.put("self", struct);
        assertThrows(IllegalArgumentException.class, () -> XTDBValues.toJdbc(struct));
    }
}
