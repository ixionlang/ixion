import com.kingmang.ixion.Ixion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class ExamplesTest {

    @Test
    void loops(){
        ixAssert("loops.ix", """
        1
        2
        3
        4
        5
        ----
        i is 10
        i is 11
        i is 12
        i is 13
        i is 14
        i is 15
        i is 16
        i is 17
        i is 18
        i is 19
        i is 20

        """);
    }

    @Test
    void simple_list(){
        ixAssert("simple_list.ix", """
                [1, 2, 3]
                [20]
        """);
    }

    @Test
    void struct(){
        ixAssert("struct.ix", """
                value[left=left_value[arg=first], right=right_value[arg=second]]
                """);
    }

    void ixAssert(String runPath, String expected) {
        Ixion api = new Ixion();
        assertDoesNotThrow(() -> {
            String actual = api.getCompiledProgramOutput("/src/test/resources/" + runPath);
            // Сравниваем с нормализацией строк
            assertEquals(
                    normalizeString(expected),
                    normalizeString(actual),
                    "Строки не совпадают после нормализации"
            );
        });
    }


    private String normalizeString(String str) {
        if (str == null) {
            return "";
        }
        String normalized = str
                .replace("\r\n", "\n")  // Windows
                .replace("\r", "\n");   // Old Mac

        String[] lines = normalized.split("\n");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (i == lines.length - 1 && trimmed.isEmpty()) {
                continue;
            }
            result.append(trimmed);
            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString().trim();
    }
}