<?php

declare(strict_types=1);

namespace RoutesApp\Bad;

final class BrokenCard
{
}
?>

<template>
    <Link route="settigns"><Text>Settings</Text></Link>
    <Link route="chat" :params="{ unread: 1 }"><Text>Chat</Text></Link>
</template>
