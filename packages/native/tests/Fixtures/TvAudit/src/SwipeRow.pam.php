<?php
namespace TvAuditApp;
use Pam\Native\Component;
final class SwipeRow extends Component
{
    public function remove(): void {}
    public function open(): void {}
}
?>
<template>
    <Column>
        <Pressable @longPress="remove"><Text>only long press</Text></Pressable>
        <Pressable @press="open" @longPress="remove"><Text>both</Text></Pressable>
        <View on:doubleTap="remove" focusable="true" />
    </Column>
</template>
