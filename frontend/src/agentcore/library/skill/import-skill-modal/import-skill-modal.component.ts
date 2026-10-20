import { HttpClient } from '@angular/common/http';
import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { AbstractControl, FormBuilder, FormControl, FormGroup, ValidationErrors } from '@angular/forms';
import { I18NEXT_NAMESPACE, I18NextModule } from 'angular-i18next';
import { Unzipped, unzipSync } from 'fflate';
import { lastValueFrom } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { SkillApi } from '@agentcore/api/skill.api';
import { I18N_NAMESPACE } from '@agentcore/constants/common';
import { IMPORT_TYPE } from '@agentcore/constants/skill';
import { i18nProviderValue, I18nService } from '@agentcore/core/i18n.service';
import { HelpDocKey } from '@constants/support-topic.const';
import { MODULES } from '@shared/modules';
import { HelpLinksService } from '@shared/services/help-links.service';
import { NzModalRef } from 'ng-zorro-antd/modal';
import { NzUploadFile } from 'ng-zorro-antd/upload';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzToolTipModule } from 'ng-zorro-antd/tooltip';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { cdnAssetUrl } from '../../../../single-spa/assets-url';
import { SkillInfo } from '../interface/skill.interface';
import { SkillCommonService } from '../services/skill.common.service';

@Component({
  selector: 'meta-import-skill',
  templateUrl: './import-skill-modal.component.html',
  styleUrls: ['./import-skill-modal.component.scss'],
  standalone: true,
  imports: [MODULES, I18NextModule, NzToolTipModule, NzButtonModule],
  providers: [
    I18nService,
    {
      provide: I18NEXT_NAMESPACE,
      useValue: i18nProviderValue([I18N_NAMESPACE.Common, I18N_NAMESPACE.Skill]),
    },
  ],
})
export class ImportSkillModalComponent {
  @Input() importType: string;
  @Output() importSuccess = new EventEmitter<string>();
  readonly modalRef = inject(NzModalRef);
  public obsForm: FormGroup;
  public selectedFile: NzUploadFile;
  public maxSize = 10 * 1024 * 1024;
  public uploadType = ['.zip'];
  public skillInfo: SkillInfo;
  public skillLink = '';
  public isShowResult = false;
  public importTypeEnum = IMPORT_TYPE;
  public importZipErrorTip = '';
  public obsfileData;
  public obsfileName = '';
  protected readonly changeUrl = cdnAssetUrl;
  public fileList: NzUploadFile[] = [];
  constructor(
    private http: HttpClient,
    fb: FormBuilder,
    private skillApi: SkillApi,
    private readonly i18n: I18nService,
    private helpLinksService: HelpLinksService,
    private skillCommonService: SkillCommonService,
    private message: NzMessageService
  ) {
    this.obsForm = fb.group({
      obsAddress: new FormControl('', [this.obsAddressValidator()]),
    });
  }

  get obsAddressValue() {
    return this.obsForm.controls?.obsAddress?.value;
  }

  get isButtonDisabled() {
    return !this.obsAddressValue || (this.obsAddressValue && this.isShowResult) || (this.obsAddressValue && !this.obsForm.valid);
  }

  ngOnInit(): void {
    this.skillLink = this.helpLinksService.getHelpCenterLink(HelpDocKey.MODELARTS_PRICES);
    this.obsForm.controls.obsAddress?.valueChanges.pipe(debounceTime(300), distinctUntilChanged()).subscribe(value => this.onObsAddressChange(value));
    this.skillCommonService.initObsEndpoint();
  }

  private obsAddressValidator() {
    const obsAddressReg = /^(https?:\/\/).+$/;
    return (control: AbstractControl): ValidationErrors | null =>
      obsAddressReg.test(control.value)
        ? null
        : {
            obsAddress: {
              tiErrorMessage: this.i18n.transform('skill.import.obs.input.valid.address'),
            },
          };
  }

  async onAddFileSuccess(fileItem: any) {
    this.importZipErrorTip = '';
    this.selectedFile = fileItem;
    const file = this.selectedFile as any;

    try {
      const arrayBuffer = await file.arrayBuffer();
      const unzipped = unzipSync(new Uint8Array(arrayBuffer));
      this.unZip(unzipped);
    } catch (error) {
      this.importZipErrorTip = this.i18n.transform('skill.import.result.null');
      this.isShowResult = true;
    }
  }

  public onAddItemFailed(info: { validResults: string[] }) {
    const error_type = info.validResults[0];
    if (error_type === 'type') {
      this.message.error(this.i18n.transform('only_support_upload_format', { types: this.uploadType.join(',') }));
    }
    if (error_type === 'maxSize') {
      this.message.error(this.i18n.transform('file_size_cannot_exceed', { size: this.maxSize / (1024 * 1024) }));
    }
  }

  public confirm() {
    const formData = new FormData();
    if (this.importType === this.importTypeEnum.ZIP) {
      formData.append('file', this.selectedFile as any);
    } else {
      const blob = new Blob([this.obsfileData], { type: 'application/x-zip-compressed' });
      formData.append('file', blob, this.obsfileName);
    }

    this.skillApi.importSkill(formData).subscribe({
      next: res => {
        this.close();
        const msg =
          this.importType === IMPORT_TYPE.OBS
            ? this.i18n.transform('skill.import_obs_success_tip')
            : this.i18n.transform('skill.import_zip_success_tip', { name: (this.selectedFile as any)?.name });
        this.message.success(msg);
        this.importSuccess.emit();
        this.modalRef.destroy();
      },
    });
  }

  public onRemoveItems() {
    if (this.selectedFile) {
      this.selectedFile = undefined;
    }
    this.isShowResult = false;
    this.skillInfo = { name: '', description: '' };
  }

  public onObsAddressChange(value: string) {
    this.skillInfo = { name: '', description: '' };
    this.isShowResult = false;
  }

  public async downloadAndUnzip() {
    const url = this.obsForm.controls.obsAddress.value;
    try {
      const buffer = await lastValueFrom(this.http.get(url, { responseType: 'arraybuffer' }));
      this.obsfileName = url.split('?')?.[0]?.split('/')?.pop() || 'default.zip';
      this.obsfileData = new Blob([buffer], { type: 'application/zip' });

      if (buffer.byteLength > this.maxSize) {
        this.message.error(this.i18n.transform('file_size_cannot_exceed', { size: this.maxSize / (1024 * 1024) }));
        return;
      }
      const data: Unzipped = unzipSync(new Uint8Array(buffer));
      this.unZip(data);
    } catch (err) {
      this.message.error(this.i18n.transform('skill.import.result.unzipError'));
    }
  }

  dismiss(): void {
    this.modalRef.destroy();
  }

  close(): void {}

  private parseSkillMd(content: string): { name: string; description: string } {
    const skill = { name: '', description: '' };
    const lines = content.split('---')[1]?.trim().split('\n') || [];

    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed.startsWith('name:')) {
        skill.name = trimmed.slice(5).trim();
      } else if (trimmed.startsWith('description:')) {
        skill.description = trimmed.slice(12).trim();
      }
    }
    return skill;
  }

  private unZip(unzipped) {
    const fileMap: Record<string, string> = {};
    for (const [originalPath, buffer] of Object.entries(unzipped)) {
      const content = new TextDecoder().decode(buffer as ArrayBuffer);
      const standardizedPath = this.normalizePath(originalPath);
      fileMap[standardizedPath] = content;
    }
    const rootFiles = Object.keys(fileMap);

    // isShowResult 语义：仅当成功解析出 SKILL.md 且 frontmatter 含 name/description 时置 true，
    // 用于控制解析结果区域的展示；任何错误分支（结构异常/缺 SKILL.md/frontmatter 缺字段）
    // 必须显式置 false，避免 UI 误展示成功状态。
    this.isShowResult = false;

    // 策略1：根级 SKILL.md（平铺结构）
    if (fileMap['SKILL.md']) {
      this.skillInfo = this.parseSkillMd(fileMap['SKILL.md']);
      if (!this.skillInfo?.name?.trim() || !this.skillInfo?.description?.trim()) {
        this.importZipErrorTip = this.i18n.transform('skill.import.result.null');
        this.isShowResult = false;
        return;
      }
      this.isShowResult = true;
      return;
    }

    // 策略2：单文件夹结构 {folderName}/SKILL.md
    const hasSingleFolder = this.hasSingleFolder(rootFiles);
    if (!hasSingleFolder) {
      this.importZipErrorTip = this.i18n.transform('skill.import.result.valid.tip1');
      return;
    }
    const folders = rootFiles.filter(path => path.includes('/'));
    const folderName = folders[0]?.split('/')?.[0];
    const skillMdPath = `${folderName}/SKILL.md`;
    if (!fileMap[skillMdPath]) {
      this.importZipErrorTip = this.i18n.transform('skill.import.result.valid.tip2');
      return;
    }
    this.skillInfo = this.parseSkillMd(fileMap[skillMdPath]);
    if (!this.skillInfo?.name?.trim() || !this.skillInfo?.description?.trim()) {
      this.importZipErrorTip = this.i18n.transform('skill.import.result.null');
      return;
    }
    this.isShowResult = true;
  }

  private normalizePath(rawPath: string): string {
    let normalized = rawPath.replace(/\\/g, '/');
    normalized = normalized.replace(/\/+/g, '/');
    normalized = normalized.replace(/\/+$/, '');
    return normalized;
  }

  private hasSingleFolder(zipFiles: string[]): boolean {
    const rootFolders = new Set(zipFiles.map(file => file.split('/')[0]));
    return rootFolders.size === 1;
  }

  handleUploadChange(fileItem: any): void {
    if (fileItem.type === 'removed') {
      this.onRemoveItems();
      return;
    }
    if (fileItem.type !== 'start') {
      return;
    }
    this.fileList = [fileItem.file];
    if (fileItem.file.name.lastIndexOf('.zip') < 0) {
      this.onAddItemFailed({ validResults: ['type'] });
      return;
    }
    this.onAddFileSuccess(fileItem.file.originFileObj);
  }
}
