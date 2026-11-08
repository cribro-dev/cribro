package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalcTest {
  @Test
  void max() {
    assertEquals(2, Calc.max(1, 2));
  }
}
