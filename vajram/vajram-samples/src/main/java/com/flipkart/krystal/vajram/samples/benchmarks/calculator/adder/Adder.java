package com.flipkart.krystal.vajram.samples.benchmarks.calculator.adder;

import com.flipkart.krystal.vajram.ComputeVajram;
import com.flipkart.krystal.vajram.VajramDef;
import com.flipkart.krystal.vajram.VajramLogic;
import com.flipkart.krystal.vajram.modulation.ModulatedInput;
import com.flipkart.krystal.vajram.samples.benchmarks.calculator.adder.AdderInputUtil.AdderCommonInputs;
import com.flipkart.krystal.vajram.samples.benchmarks.calculator.adder.AdderInputUtil.AdderInputsNeedingModulation;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Function;

@VajramDef("adder")
public abstract class Adder extends ComputeVajram<Integer> {
  @VajramLogic
  public static Map<AdderInputsNeedingModulation, Integer> add(
      ModulatedInput<AdderInputsNeedingModulation, AdderCommonInputs> modulatedInput) {
    return modulatedInput.inputsNeedingModulation().stream()
        .collect(
            ImmutableMap.toImmutableMap(
                Function.identity(), im -> add(im.numberOne(), im.numberTwo().orElse(0))));
  }

  public static int add(int a, int b) {
    return a + b;
  }
}
