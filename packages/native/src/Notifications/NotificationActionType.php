<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\NotificationActionType', \Pam\Native\Notifications\Contracts\NotificationActionType::class, 'pushinbr/pam-native-notifications');
