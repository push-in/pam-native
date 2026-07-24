<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

final readonly class PamPhpComponent
{
    public function __construct(
        public string $className,
        public string $tag,
        public string $source,
        public string $classFile,
        public CompiledTemplateNode $template,
    ) {
    }
}
