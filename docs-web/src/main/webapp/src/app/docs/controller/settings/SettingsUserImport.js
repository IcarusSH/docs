'use strict';

/**
 * Settings user CSV import modal controller.
 * Opens from SettingsUser.openImport(). Uploads a CSV file as multipart/form-data
 * and shows a per-row summary on success.
 */
angular.module('docs').controller('SettingsUserImport', function($scope, $uibModalInstance, $translate, $dialog) {
  $scope.file = null;
  $scope.busy = false;
  $scope.result = null;
  $scope.error = null;

  // Sample CSV template (header + 3 example rows). Exposed for the "Download template"
  // button; the user can edit it locally before uploading.
  $scope.templateCsv =
      'username,email,role,storage_quota_mb,update\n' +
      'alice,alice@example.com,user,1024,\n' +
      'bob,bob@example.com,admin,2048,overwrite\n' +
      'charlie,charlie@example.com,user,512,patch\n';

  // Trigger a browser download of the sample CSV.
  $scope.downloadTemplate = function() {
    var blob = new Blob([$scope.templateCsv], { type: 'text/csv;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'teedy-users-template.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function() { URL.revokeObjectURL(url); }, 0);
  };

  // Called from the file input's onchange attribute. Wrapped in $apply so
  // AngularJS picks up the change and re-evaluates ng-disabled on the upload button.
  $scope.onFileChange = function(files) {
    if (files && files.length) {
      $scope.file = files[0];
      $scope.error = null;
    } else {
      $scope.file = null;
    }
    $scope.$apply();
  };

  $scope.upload = function() {
    if (!$scope.file) {
      $scope.error = $translate.instant('settings.user.import_no_file');
      return;
    }
    $scope.busy = true;
    $scope.error = null;
    var fd = new FormData();
    fd.append('file', $scope.file);
    // Use raw XMLHttpRequest so we can send multipart/form-data without Content-Type mangling.
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '../api/user/import');
    xhr.withCredentials = true;
    xhr.onload = function() {
      $scope.busy = false;
      if (xhr.status === 200) {
        try {
          $scope.result = JSON.parse(xhr.responseText);
        } catch (e) {
          $scope.error = $translate.instant('settings.user.import_parse_error');
        }
      } else if (xhr.status === 403) {
        $scope.error = $translate.instant('settings.user.import_forbidden');
      } else {
        try {
          var body = JSON.parse(xhr.responseText);
          $scope.error = (body && body.message) ? body.message : $translate.instant('settings.user.import_failed');
        } catch (e) {
          $scope.error = $translate.instant('settings.user.import_failed');
        }
      }
      $scope.$apply();
    };
    xhr.onerror = function() {
      $scope.busy = false;
      $scope.error = $translate.instant('settings.user.import_network_error');
      $scope.$apply();
    };
    xhr.send(fd);
  };

  $scope.close = function() {
    $uibModalInstance.dismiss();
  };

  $scope.done = function() {
    $uibModalInstance.close();
  };

  // Helper for rendering result line items
  $scope.formatRow = function(item) {
    return '#' + item.line + (item.username ? ' ' + item.username : '');
  };

  $scope.formatErrorMessage = function(item) {
    return $translate.instant('settings.user.import_error_message', {
      line: item.line,
      username: item.username || '',
      message: item.message
    });
  };
});