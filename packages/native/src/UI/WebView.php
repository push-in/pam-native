<?php
declare(strict_types=1);
namespace Pam\Native\UI;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\WebView', \Pam\Native\WebView\WebView::class, 'pushinbr/pam-native-webview');
