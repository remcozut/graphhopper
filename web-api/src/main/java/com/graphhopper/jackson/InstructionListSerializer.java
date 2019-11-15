package com.graphhopper.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.graphhopper.util.Helper;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionAnnotation;
import com.graphhopper.util.InstructionList;

import java.io.IOException;
import java.util.*;

public class InstructionListSerializer extends JsonSerializer<InstructionList> {
    @Override
    public void serialize(InstructionList instructions, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        List<Map<String, Object>> instrList = new ArrayList<>(instructions.size());
        int pointsIndex = 0;
        for (Instruction instruction : instructions) {
            Map<String, Object> instrJson = new HashMap<>();
            instrList.add(instrJson);

            InstructionAnnotation ia = instruction.getAnnotation();
            String text = instruction.getTurnDescription(instructions.getTranslation());
            if (Helper.isEmpty(text))
                text = ia.getMessage();

            if (instruction.getExtraInfoJSON().containsKey("crossing")) {
                if (! Helper.isEmpty(text)) {
                    text += ", ";
                }
                text += instructions.getTranslation().tr("crossing");
            }

            instrJson.put("text", Helper.firstBig(text));

            if (!ia.isEmpty()) {
                instrJson.put("annotation_text", ia.getMessage());
                instrJson.put("annotation_importance", ia.getImportance());
            }

            instrJson.put("street_name", instruction.getName());
            instrJson.put("time", instruction.getTime());
            instrJson.put("distance", Helper.round(instruction.getDistance(), 3));
            instrJson.put("sign", instruction.getSign());
            instrJson.put("turn_type", instruction.getTurnType().getValue());
            instrJson.putAll(instruction.getExtraInfoJSON());

            int tmpIndex = pointsIndex + instruction.getLength();
            instrJson.put("interval", Arrays.asList(pointsIndex, tmpIndex));
            pointsIndex = tmpIndex;

        }
        jsonGenerator.writeObject(instrList);
    }
}
