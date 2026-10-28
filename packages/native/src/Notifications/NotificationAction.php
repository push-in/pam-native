<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\NotificationAction', \Pam\Native\Notifications\Contracts\NotificationAction::class, 'pushinbr/pam-native-notifications');
