package com.flipkart.krystal.vajram.samples.benchmarks.calculator.subtractor;

import com.flipkart.krystal.vajram.ComputeVajram;
import com.flipkart.krystal.vajram.VajramDef;
import com.flipkart.krystal.vajram.VajramLogic;
import com.flipkart.krystal.vajram.samples.benchmarks.calculator.subtractor.SubtractorInputUtil.SubtractorAllInputs;

@VajramDef("subtractor")
public abstract class Subtractor extends ComputeVajram<Integer> {
  @VajramLogic
  public int subtract(SubtractorAllInputs allInputs) {
    return allInputs.numberOne() - allInputs.numberTwo().orElse(0);
  }
}
