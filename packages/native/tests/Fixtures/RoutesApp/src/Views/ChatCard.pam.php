<?php

declare(strict_types=1);

namespace RoutesApp\Views;

use Pam\Native\Component;

final class ChatCard extends Component
{
    public string $id = '';
}
?>

<template>
    <Link route="chat" :params="{ id: id, unread: 3 }"><Text>Open</Text></Link>
</template>
