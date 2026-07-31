<?php

declare(strict_types=1);

namespace Pam\Native\Worklets;

use InvalidArgumentException;

/**
 * A bounded, data-only numeric program intended for native frame execution.
 * It cannot call PHP functions, allocate objects, access files, or perform I/O.
 */
final readonly class Worklet
{
    private const MAX_INSTRUCTIONS = 256;

    /** @param list<array{opcode: int, operands: list<float>}> $instructions */
    private function __construct(private array $instructions)
    {
        if ($instructions === [] || count($instructions) > self::MAX_INSTRUCTIONS) {
            throw new InvalidArgumentException('Worklets require between 1 and 256 instructions.');
        }
    }

    public static function input(): self
    {
        return new self([['opcode' => WorkletOpcode::Input->value, 'operands' => []]]);
    }

    public static function constant(float $value): self
    {
        self::finite($value);
        return new self([['opcode' => WorkletOpcode::Constant->value, 'operands' => [$value]]]);
    }

    public function add(float $value): self
    {
        return $this->append(WorkletOpcode::Add, [$value]);
    }

    public function subtract(float $value): self
    {
        return $this->append(WorkletOpcode::Subtract, [$value]);
    }

    public function multiply(float $value): self
    {
        return $this->append(WorkletOpcode::Multiply, [$value]);
    }

    public function divide(float $value): self
    {
        if ($value == 0.0) {
            throw new InvalidArgumentException('Worklets cannot divide by zero.');
        }
        return $this->append(WorkletOpcode::Divide, [$value]);
    }

    public function clamp(float $minimum, float $maximum): self
    {
        if ($minimum > $maximum) {
            throw new InvalidArgumentException('Worklet clamp minimum cannot exceed maximum.');
        }
        return $this->append(WorkletOpcode::Clamp, [$minimum, $maximum]);
    }

    public function interpolate(
        float $inputMinimum,
        float $inputMaximum,
        float $outputMinimum,
        float $outputMaximum,
    ): self {
        if ($inputMinimum === $inputMaximum) {
            throw new InvalidArgumentException('Worklet interpolation input range cannot be empty.');
        }
        return $this->append(WorkletOpcode::Interpolate, [
            $inputMinimum,
            $inputMaximum,
            $outputMinimum,
            $outputMaximum,
        ]);
    }

    public function evaluate(float $input): float
    {
        self::finite($input);
        $value = 0.0;
        foreach ($this->instructions as $instruction) {
            $operands = $instruction['operands'];
            $value = match (WorkletOpcode::from($instruction['opcode'])) {
                WorkletOpcode::Input => $input,
                WorkletOpcode::Constant => $operands[0],
                WorkletOpcode::Add => $value + $operands[0],
                WorkletOpcode::Subtract => $value - $operands[0],
                WorkletOpcode::Multiply => $value * $operands[0],
                WorkletOpcode::Divide => $value / $operands[0],
                WorkletOpcode::Clamp => min(max($value, $operands[0]), $operands[1]),
                WorkletOpcode::Interpolate => $operands[2]
                    + (($value - $operands[0]) / ($operands[1] - $operands[0]))
                    * ($operands[3] - $operands[2]),
            };
            self::finite($value);
        }
        return $value;
    }

    public function bytecode(): string
    {
        $bytes = 'PNW1'.pack('v', count($this->instructions));
        foreach ($this->instructions as $instruction) {
            $bytes .= pack('CC', $instruction['opcode'], count($instruction['operands']));
            foreach ($instruction['operands'] as $operand) {
                $bytes .= pack('e', $operand);
            }
        }
        return $bytes;
    }

    /** @param list<float> $operands */
    private function append(WorkletOpcode $opcode, array $operands): self
    {
        foreach ($operands as $operand) self::finite($operand);
        return new self([...$this->instructions, [
            'opcode' => $opcode->value,
            'operands' => $operands,
        ]]);
    }

    private static function finite(float $value): void
    {
        if (!is_finite($value)) {
            throw new InvalidArgumentException('Worklet operands must be finite.');
        }
    }
}
