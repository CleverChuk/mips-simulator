package com.cleverchuk.mips.compiler.codegen;

import com.cleverchuk.mips.compiler.lexer.MipsLexer;
import com.cleverchuk.mips.compiler.parser.Construct;
import com.cleverchuk.mips.compiler.parser.Node;
import com.cleverchuk.mips.compiler.parser.NodeType;
import com.cleverchuk.mips.compiler.parser.SymbolTable;
import com.cleverchuk.mips.emulator.BigEndianMainMemory;
import com.cleverchuk.mips.emulator.Instruction;
import com.cleverchuk.mips.emulator.Memory;
import com.cleverchuk.mips.emulator.Opcode;
import com.cleverchuk.mips.emulator.storage.StorageType;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import javax.inject.Inject;
import lombok.Data;

@Data
public final class CodeGenerator {
    public static final char VALUE_DELIMITER = '#';

    private final Memory memory = new BigEndianMainMemory(1024);

    private final List<Instruction> instructions = new ArrayList<>();

    private int dataSegmentOffset = -1;

    private int textSegmentOffset = -1;

    private int memOffset = 0;

    @Inject
    public CodeGenerator() {
    }

    public void flush() {
        dataSegmentOffset = -1;
        textSegmentOffset = -1;
        memOffset = 0;
        instructions.clear();
    }

    public void generate(Node root) {
        for (Node child : root.getChildren()) {
            generate(child);
            root.setValue(concatenate(root.getValue(), child.getValue()));
        }

        if (root.getNodeType() == NodeType.NONTERMINAL) {
            switch (root.getConstruct()) {
                case TERM:
                case EXPR:
                    root.setValue(evalExpr(root.getValue()));
                    break;

                case ZEROOP:
                    instructions.add(buildInstruction(root.getChildren().get(0).getLine(),
                            root.getChildren().get(0).getValue().toString(), null,
                            null, null, null));
                    break;

                case ONEOP:
                    instructions.add(buildInstruction(root.getChildren().get(0).getLine(),
                            root.getChildren().get(0).getValue().toString(),
                            root.getChildren().get(1).getValue().toString(), null,
                            null, null));
                    break;

                case TWOOP:
                    instructions.add(buildInstruction(root.getChildren().get(0).getLine(),
                            root.getChildren().get(0).getValue().toString(),
                            root.getChildren().get(1).getValue().toString(),
                            root.getChildren().get(2).getValue().toString(), null, null));
                    break;

                case THREEOP:
                    instructions.add(buildInstruction(root.getChildren().get(0).getLine(),
                            root.getChildren().get(0).getValue().toString(),
                            root.getChildren().get(1).getValue().toString(),
                            root.getChildren().get(2).getValue().toString(),
                            root.getChildren().get(3).getValue().toString(), null));
                    break;

                case FOUROP:
                    instructions.add(buildInstruction(root.getChildren().get(0).getLine(),
                            root.getChildren().get(0).getValue().toString(),
                            root.getChildren().get(1).getValue().toString(),
                            root.getChildren().get(2).getValue().toString(),
                            root.getChildren().get(3).getValue().toString(),
                            root.getChildren().get(4).getValue().toString()));
                    break;

                case DATADECL:
                    loadMemory(root.getValue().toString());
                    break;

                case INSTRUCTION:
                    if (textSegmentOffset < 0) {
                        textSegmentOffset = root.getLine();
                    }
                    break;

                case TEXTDECL:
                    Node label = root.getChildren().get(0);
                    if (label.getConstruct() == Construct.LABEL) {
                        SymbolTable.insert(label.getValue().toString(), instructions.size() - 1);
                    }
                    break;

                case TEXTSEG:
                    textSegmentOffset = root.getLine();
                    break;

                case DATASEG:
                    dataSegmentOffset = root.getLine();
                    break;
            }
        }
    }

    private Object concatenate(Object first, Object second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.toString() + VALUE_DELIMITER + second.toString();
    }

    private Object evalExpr(Object expr) {
        String[] tokens = expr.toString().split("#");
        Stack<Integer> stack = new Stack<>();

        String token;
        int len = tokens.length - 1;
        boolean negate = false;
        for (int i = 0; i < len; i++) {
            token = tokens[i];
            char c = token.charAt(0);
            if (token.length() > 1) {
                c = 0;
            }
            if (i == 0 && c == '-') {
                negate = true;
            } else if (isOp(c)) {
                int l = stack.pop();
                int r;
                if (tokens[i + 1].charAt(0) == '-' && i + 2 <= len) {
                    i++;
                    r = -1 * Integer.parseInt(tokens[i + 1]);
                } else {
                    r = Integer.parseInt(tokens[i + 1]);
                }
                if (token.charAt(0) == '+') {
                    stack.push(l + r);
                } else if (token.charAt(0) == '-') {
                    stack.push(l - r);
                } else if (token.charAt(0) == '*') {
                    stack.push(l * r);
                } else {
                    stack.push(l / r);
                }
            } else {
                if (negate) {
                    negate = false;
                    stack.push(-1 * Integer.parseInt(token));
                } else {
                    stack.push(Integer.parseInt(token));
                }
            }
        }
        if (stack.isEmpty()) {
            return tokens[0];
        }
        return stack.pop();
    }

    private boolean isOp(char c) {
        return c == '-' || c == '+' || c == '*' || c == '/';
    }

    private Instruction buildInstruction(int line, String opcode, String operand0, String operand1, String operand2, String operand3) {
        Opcode opCode = Opcode.valueOf(opcode.toUpperCase());
        Instruction.InstructionBuilder builder = Instruction.builder()
                .line(line)
                .opcode(opCode);

        if (operand0 == null) { // Zero operand opcode
            return builder
                    .build();
        } else if (operand1 == null) { // One operand opcode
            if (MipsLexer.isRegister(operand0)) {
                builder.rd("$" + operand0);
            } else {
                try {
                    builder.immediateValue(Integer.parseInt(operand0));
                } catch (Exception e) {
                    builder.label(operand0);
                }
            }
            return builder.build();

        } else if (operand2 == null) {// Two operand opcode
            if (MipsLexer.isRegister(operand0)) {
                builder.rd("$" + operand0);
            }

            if (MipsLexer.isRegister(operand1)) {
                builder.rs("$" + operand1);
            } else if (operand1.contains("#")) {
                String[] tokens = operand1.split("#");
                builder.offset(Integer.parseInt(tokens[0]));
                builder.rs("$" + tokens[1]);
            } else {
                try {
                    builder.immediateValue(Integer.parseInt(operand1));
                } catch (Exception e) {
                    builder.label(operand1);
                }
            }
            return builder.build();

        } else if (operand3 == null) { // Three operand opcode

            if (MipsLexer.isRegister(operand0)) {
                builder.rd("$" + operand0);
            }

            if (MipsLexer.isRegister(operand1)) {
                builder.rs("$" + operand1);
            }

            if (MipsLexer.isRegister(operand2)) {
                builder.rt("$" + operand2);
            } else if (operand2.contains("#")) {// Check that offset is non-negative
                String[] tokens = operand2.split("#");
                builder.offset(Integer.parseInt(tokens[0]));
                builder.rt("$" + tokens[1]);

            } else {
                try {
                    builder.immediateValue(Integer.parseInt(operand2));
                } catch (Exception e) {
                    builder.label(operand2);
                }
            }
            return builder.build();

        } else { // Four operand opcode
            return builder.rd("$" + operand0)
                    .rs("$" + operand1)
                    .pos(Integer.parseInt(operand2))
                    .size(Integer.parseInt(operand3))
                    .build();
        }
    }

    private void loadMemory(String data) {
        String[] tokens = data.split("#");
        String label = tokens[0];
        String type = tokens[1].toUpperCase();

        SymbolTable.insert(label, memOffset);
        switch (StorageType.valueOf(type)) {
            case SPACE:
                memOffset += Integer.parseInt(tokens[2]);
                break;

            case WORD:
            case INT:
                for (int i = 2; i < tokens.length; i++, memOffset += 4) {
                    memory.storeWord(Integer.parseInt(tokens[i]), memOffset);
                }
                break;

            case BYTE:
            case CHAR:
                for (int i = 2; i < tokens.length; i++, memOffset++) {
                    memory.store(Byte.parseByte(tokens[i]), memOffset);
                }
                break;

            case HALF:
                for (int i = 2; i < tokens.length; i++, memOffset += 2) {
                    memory.storeHalf(Short.parseShort(tokens[i]), memOffset);
                }
                break;

            case ASCIIZ:
                writeASCII(tokens);
                memory.store((byte) 0, memOffset++);
                break;

            case ASCII:
                writeASCII(tokens);
                break;
        }
    }

    private void writeASCII(String[] tokens) {
        int start = tokens[2].indexOf('"') + 1, end = tokens[2].lastIndexOf('"');
        char[] temp = tokens[2].substring(start, end).toCharArray();
        for (int i = 0; i < temp.length; i++, memOffset++) {

            if (temp[i] == '\\' && i + 1 < temp.length && temp[i + 1] == 'n') {
                memory.store((byte) 10, memOffset);
                i++;
            } else {
                memory.store((byte) temp[i], memOffset);
            }
        }
    }
}