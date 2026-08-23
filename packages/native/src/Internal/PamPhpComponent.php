<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\LanguageVersion;

final readonly class PamPhpComponent
{
    public function __construct(
        public string $className,
        public string $tag,
        public string $source,
        public string $classFile,
        public CompiledTemplateNode $template,
        public LanguageVersion $language = LanguageVersion::Language1,
    ) {
    }
}
