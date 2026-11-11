<?php

declare(strict_types=1);

namespace VariantsApp;

use Pam\Native\Component;

final class VariantCard extends Component
{
    public string $title = 'Night Shift';
}
?>

<template>
    <Text>phone {{ $title }}</Text>
</template>
