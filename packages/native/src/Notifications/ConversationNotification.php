<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\ConversationNotification', \Pam\Native\Notifications\Contracts\ConversationNotification::class, 'pushinbr/pam-native-notifications');
