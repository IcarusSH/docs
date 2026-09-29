'use strict';

/**
 * Settings user page controller.
 */
angular.module('docs').controller('SettingsUser', function($scope, $rootScope, $state, $uibModal, Restangular, $dialog, $translate) {
  /**
   * Load users from server.
   */
  $scope.loadUsers = function() {
    Restangular.one('user/list').get({
      sort_column: 1,
      asc: true
    }).then(function(data) {
      $scope.users = data.users;
    });
  };

  $scope.loadUsers();

  /**
   * Edit a user.
   */
  $scope.editUser = function(user) {
    $state.go('settings.user.edit', { username: user.username });
  };

  /**
   * Open the CSV import modal. User picks a file; we upload it as multipart/form-data
   * and surface a summary of created / updated / skipped / errors.
   */
  $scope.openImport = function() {
    $uibModal.open({
      templateUrl: 'partial/docs/settings.user.import.html',
      controller: 'SettingsUserImport'
    }).result.then(function() {
      $scope.loadUsers();
    }, angular.noop);
  };

  /**
   * Start impersonating a user. Asks for confirmation, then reloads the page so the
   * navigation / ACL state reflect the impersonated user's view.
   */
  $scope.impersonate = function(user) {
    var title = $translate.instant('settings.user.impersonate_confirm_title', { username: user.username });
    var msg = $translate.instant('settings.user.impersonate_confirm_message', { username: user.username });
    var btns = [
      {result: 'cancel', label: $translate.instant('cancel')},
      {result: 'ok', label: $translate.instant('settings.user.impersonate_btn'), cssClass: 'btn-warning'}
    ];
    $dialog.messageBox(title, msg, btns, function(result) {
      if (result !== 'ok') return;
      Restangular.one('admin/impersonate').post('', { username: user.username }).then(function() {
        // Hard reload so the entire app re-bootstraps as the impersonated user
        // (Main.js calls User.userInfo() again on bootstrap).
        window.location.reload();
      }, function() {
        var errTitle = $translate.instant('settings.user.impersonate_failed_title');
        var errMsg = $translate.instant('settings.user.impersonate_failed_message');
        var errBtns = [{result: 'ok', label: $translate.instant('ok'), cssClass: 'btn-primary'}];
        $dialog.messageBox(errTitle, errMsg, errBtns);
      });
    });
  };
});