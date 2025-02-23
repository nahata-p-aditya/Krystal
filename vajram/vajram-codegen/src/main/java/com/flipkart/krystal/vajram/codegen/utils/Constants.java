package com.flipkart.krystal.vajram.codegen.utils;

public final class Constants {

    public static final String VAL_ERR = "valErr";
    public static final String DEP_RESP = "depResp";
    public static final String RESOLVABLE_INPUTS = "resolvableInputs";
    public static final String INPUT_SRC = "inputSrc";
    public static final String INPUT_MODULATION = "inputModulation";
    public static final String COMMON_INPUT = "commonInput";
    public static final String RETURN_TYPE = "returnType";
    public static final String VAJRAM_LOGIC_METHOD = "vajramLogicMethod";
    public static final String HASH_MAP = "hashMap";
    public static final String ARRAY_LIST = "arrayList";
    public static final String COM_FUTURE = "comFuture";
    public static final String LINK_HASH_MAP = "linkHashMap";
    public static final String MAP = "map";
    public static final String LIST = "list";
    public static final String JAVA_EXT = ".java";

    private Constants() {}

    public static final char DOT_SEPARATOR = '.';
    public static final String RESPONSE = "response";
    public static final String VARIABLE = "variable";
    public static final String DEP_RESPONSE = "depResponse";
    public static final String REQUEST = "request";
    public static final String RESPONSES_SUFFIX = "Responses";
    public static final String RESPONSE_SUFFIX = "Response";
    public static final String METHOD_GET_INPUTS_CONVERTOR = "getInputsConvertor";
    public static final String METHOD_EXECUTE = "execute";
    public static final String METHOD_RESOLVE_INPUT_OF_DEPENDENCY = "resolveInputOfDependency";
    public static final String METHOD_EXECUTE_COMPUTE = "executeCompute";
    public static final String GET_INPUT_DEFINITIONS = "getInputDefinitions";
    public static final String ALL_INPUTS = "AllInputs";
    public static final String INPUTS_LIST = "inputsList";
    public static final String INPUTS_NEEDING_MODULATION = "InputsNeedingModulation";
    public static final String COMMON_INPUTS = "CommonInputs";
    public static final String INPUTS = "inputs";
    public static final String UNMOD_INPUT = "unmodInput";
    public static final String MOD_INPUT = "modInput";
    public static final String IM_MAP = "imMap";
    public static final String IM_LIST = "imList";
    public static final String DEP_COMMAND = "depCommand";
    public static final String FUNCTION = "function";

    public static final String INPUT_MODULATION_CODE_BLOCK = """
                $map:T<$inputModulation:T, $inputs:T> mapping = new $hashMap:T<>();
                $list:T<$inputModulation:T> ims = new $arrayList:T<>();
                $commonInput:T commonInputs = null;
                for ($inputs:T inputs : inputsList) {
                  $unmodInput:T<$inputModulation:T, $commonInput:T> allInputs =
                      getInputsConvertor().apply(inputs);
                  commonInputs = allInputs.commonInputs();
                  $inputModulation:T im = allInputs.inputsNeedingModulation();
                  mapping.put(im, inputs);
                  ims.add(im);
                }
                $map:T<$inputs:T, $valErr:T<$returnType:T>> returnValue = new $linkHashMap:T<>();
                            
                if (commonInputs != null) {
                  var results = $vajramLogicMethod:L(new $modInput:T<>($imList:T.copyOf(ims), commonInputs));
                  results.forEach((im, value) -> returnValue.put(mapping.get(im), $valErr:T.withValue(value)));
                }
                return $imMap:T.copyOf(returnValue);
            """;

    public static final String INPUT_MODULATION_FUTURE_CODE_BLOCK = """
                $map:T<$inputModulation:T, $inputs:T> mapping = new $hashMap:T<>();
                $list:T<$inputModulation:T> ims = new $arrayList:T<>();
                $commonInput:T commonInputs = null;
                for ($inputs:T inputs : inputsList) {
                  $unmodInput:T<$inputModulation:T, $commonInput:T> allInputs =
                      getInputsConvertor().apply(inputs);
                  commonInputs = allInputs.commonInputs();
                  $inputModulation:T im = allInputs.inputsNeedingModulation();
                  mapping.put(im, inputs);
                  ims.add(im);
                }
                $map:T<$inputs:T, $comFuture:T<$returnType:T>> returnValue = new $linkHashMap:T<>();
                            
                if (commonInputs != null) {
                  var results = $vajramLogicMethod:L(new $modInput:T<>($imList:T.copyOf(ims), commonInputs));
                  results.forEach((im, future) -> returnValue.put(mapping.get(im), future));
                }
                return $imMap:T.copyOf(returnValue);
            """;
}
